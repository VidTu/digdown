/*
 * digdown is a third-party PaperMC plugin for Minecraft Java Edition
 * that forces the warden to tick even in non-simulated chunks.
 *
 * Copyright (C) 2026 VidTu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package ru.vidtu.digdown;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import com.google.errorprone.annotations.DoNotCall;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Main digdown entrypoint.
///
/// @author VidTu
/// @apiNote Internal use only
@ApiStatus.Internal
@NullMarked
public final class Entrypoint extends JavaPlugin implements Listener {
    /// A `final` server instance.
    @SuppressWarnings("FieldNotUsedInToString") // <- Cyclic.
    private final Server server;

    /// Logger for this plugin.
    private final Logger logger = this.getSLF4JLogger();

    /// Map of ticked wardens mapped to their ticking tasks.
    ///
    /// @apiNote This map is thread-safe
    /// @see #onEntityAddToWorldEvent(EntityAddToWorldEvent)
    /// @see #onEntityRemoveFromWorldEvent(EntityRemoveFromWorldEvent)
    private final Map<Warden, ScheduledTask> tickingWardens = new ConcurrentHashMap<>(0);

    /// Creates a new plugin.
    ///
    /// @apiNote Do not call, called by the server
    @Contract(pure = true)
    public Entrypoint() {
        // Assign.
        this.server = this.getServer();
    }

    /// Initializes the plugin.
    ///
    /// @apiNote Do not call, called by superclass
    @DoNotCall("Called by superclass")
    @Override
    public void onEnable() {
        // Wrap.
        try {
            // Log.
            final Logger logger = this.logger;
            logger.info("digdown: Starting...");

            // Preload internals. (call clinit inside it)
            Class.forName(Internals.class.getName(), /*initialize=*/true, Internals.class.getClassLoader());

            // Register the handler.
            this.server.getPluginManager().registerEvents(this, this);

            // Log.
            logger.info("digdown: Hi!");
        } catch (final Throwable t) {
            // Wrap. (x2)
            try {
                // Try to shut down.
                this.server.shutdown();
            } catch (final Throwable th) {
                // Suppress.
                t.addSuppressed(th);
            }

            // Rethrow.
            throw new RuntimeException("digdown: Unable to init.", t);
        }
    }

    /// Processes the addition of a [Warden] into the world.
    ///
    /// Starts a ticking task, adds the task into the [#tickingWardens] map for the task
    /// to be cancelled later in [#onEntityRemoveFromWorldEvent(EntityRemoveFromWorldEvent)].
    ///
    /// @param event Event to handle
    /// @apiNote Do not call, called by [`@EventHandler`][EventHandler]
    /// @see #tickingWardens
    /// @see #onEntityRemoveFromWorldEvent(EntityRemoveFromWorldEvent)
    /// @see #tickWarden(Warden, Object, ScheduledTask)
    @DoNotCall("Called by @EventHandler")
    @EventHandler(ignoreCancelled = true)
    private void onEntityAddToWorldEvent(final EntityAddToWorldEvent event) {
        // Wrap.
        try {
            // Skip, if ANY SINGLE ONE of these conditions are met:
            // - The entity is NOT warden.
            // - The warden is no longer a valid entity. (e.g., got removed by some plugin)
            // - The warden is persistent. (e.g., via nametag)
            if (!(event.getEntity() instanceof final Warden paperWarden) || // Implicit NPE for 'event'
                    !paperWarden.isValid() || !paperWarden.getRemoveWhenFarAway()) return;

            // Extract the implementation.
            final Object mojangWarden = Internals.paperWardenToMojangWarden(paperWarden);

            // Start an update task.
            final ScheduledTask task = paperWarden.getScheduler().runAtFixedRate(this,
                    (final ScheduledTask innerTask) -> this.tickWarden(paperWarden, mojangWarden, innerTask),
                    /*retired=*/null, /*initialDelayTicks=*/1L, /*periodTicks=*/1L);

            // Add the task to the ticking map. (and cancel any previous task)
            if (task == null) return;
            final ScheduledTask oldTask = this.tickingWardens.put(paperWarden, task);
            if (oldTask == null) return;
            oldTask.cancel();
        } catch (final Throwable t) {
            // Rethrow.
            throw new RuntimeException("digdown: Unable to handle entity creation. (event: " + event + ')', t);
        }
    }

    /// Processes the removal of a [Warden] from the world.
    ///
    /// Stops and removes a ticking task (if any) from the [#tickingWardens] map.
    ///
    /// @param event Event to handle
    /// @apiNote Do not call, called by [`@EventHandler`][EventHandler]
    /// @see #tickingWardens
    /// @see #onEntityAddToWorldEvent(EntityAddToWorldEvent)
    @DoNotCall("Called by @EventHandler")
    @EventHandler(ignoreCancelled = true)
    private void onEntityRemoveFromWorldEvent(final EntityRemoveFromWorldEvent event) {
        // Skip, if the entity is NOT warden.
        if (!(event.getEntity() instanceof final Warden paperWarden)) return; // Implicit NPE for 'event'

        // Remove the task from the ticking map. (and cancel it)
        final ScheduledTask oldTask = this.tickingWardens.remove(paperWarden);
        if (oldTask == null) return;
        oldTask.cancel();
    }

    /// Ticks the warden.
    ///
    /// @param paperWarden  Paper (API) warden instance
    /// @param mojangWarden Mojang (implementation) warden instance
    /// @param task         Task that currently ticks the warden
    private void tickWarden(final Warden paperWarden, final Object mojangWarden, final ScheduledTask task) {
        // Wrap.
        try {
            // Cancel the task, if the warden meets ANY SINGLE ONE of these conditions:
            // - Cannot be controlled by the current thread anymore.
            // - Is no longer a valid entity.
            // - Got persistent. (e.g., via nametag)
            if (!this.server.isOwnedByCurrentRegion(paperWarden) || // Implicit NPE for 'paperWarden'
                    !paperWarden.isValid() || !paperWarden.getRemoveWhenFarAway()) {
                task.cancel(); // Implicit NPE for 'task'
                return;
            }

            // Do nothing, if the warden is currently being ticked by itself.
            if (paperWarden.isTicking()) return;

            // Tick the warden.
            Internals.tick(mojangWarden); // Implicit NPE for 'mojangWarden'
        } catch (final Throwable t) {
            // Wrap. (x2)
            try {
                // Try to cancel the task.
                task.cancel(); // Implicit NPE for 'task'
            } catch (final Throwable th) {
                // Suppress.
                t.addSuppressed(th);
            }

            // Rethrow.
            throw new RuntimeException("digdown: Unable to tick the warden. (paperWarden: " + paperWarden + ", mojangWarden: " + mojangWarden + ", task: " + task + ')', t);
        }
    }

    @Contract(pure = true)
    @Override
    public String toString() {
        return "digdown/Entrypoint{" +
                "tickingWardens=" + this.tickingWardens +
                '}';
    }
}
