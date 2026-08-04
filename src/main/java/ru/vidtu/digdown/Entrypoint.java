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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Server;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NullMarked;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Main digdown entrypoint.
///
/// @author VidTu
/// @apiNote Internal use only
@ApiStatus.Internal
@NullMarked
public final class Entrypoint extends JavaPlugin implements Listener {
    /// Logger for this class.
    private static final Logger LOGGER = LogManager.getLogger("digdown/Entrypoint");

    /// Map of ticked wardens mapped to their ticking tasks.
    ///
    /// @apiNote This map is thread-safe
    /// @see #onEntityAddToWorldEvent(EntityAddToWorldEvent)
    /// @see #onEntityRemoveFromWorldEvent(EntityRemoveFromWorldEvent)
    private static final Map<Warden, ScheduledTask> TICKING_WARDENS = new ConcurrentHashMap<>(0);

    /// A `final` server instance.
    private final Server server;

    /// Creates a new plugin.
    ///
    /// @apiNote Do not call, called by the server
    @Contract(pure = true)
    public Entrypoint() {
        // Obtain the server instance.
        this.server = this.getServer();
    }

    /// Initializes the plugin.
    ///
    /// @apiNote Do not call, called by superclass
    @DoNotCall("Called by superclass")
    @Override
    public void onEnable() {
        try {
            // Log.
            LOGGER.info("digdown: Starting...");

            // Preload internals. (call clinit inside it)
            Class.forName(Internals.class.getName(), /*initialize=*/true, Internals.class.getClassLoader());

            // Register the handler.
            this.server.getPluginManager().registerEvents(this, this);

            // Log.
            LOGGER.info("digdown: Hi!");
        } catch (final Throwable t) {
            // Log, shutdown, rethrow. (**ERROR**)
            LOGGER.error("digdown: Unable to init.", t);
            this.server.shutdown();
            throw new RuntimeException("digdown: Unable to init.", t);
        }
    }

    /// Processes the addition of a [Warden] into the world.
    ///
    /// Starts a ticking task, adds the task into the [#TICKING_WARDENS] map for the task
    /// to be cancelled later in [#onEntityRemoveFromWorldEvent(EntityRemoveFromWorldEvent)].
    ///
    /// @param event Event to handle
    /// @apiNote Do not call, called by [`@EventHandler`][EventHandler]
    /// @see #TICKING_WARDENS
    /// @see #onEntityRemoveFromWorldEvent(Entity)
    @DoNotCall("Called by @EventHandler")
    @EventHandler(ignoreCancelled = true)
    private void onEntityAddToWorldEvent(final EntityAddToWorldEvent event) {
        // Skip, if ANY SINGLE ONE of these conditions are met:
        // - The entity is NOT warden.
        // - The warden is no longer a valid entity. (e.g., got removed by some plugin)
        // - The warden is persistent. (e.g., via nametag)
        if (!(event.getEntity() instanceof final Warden warden) || // Implicit NPE for 'event'
            !warden.isValid() || !warden.getRemoveWhenFarAway()) return;

        // Extract the implementation.
        final Object mojangWarden = Internals.paperWardenToMojangWarden(warden);

        // Start an update task.
        final Server server = this.server;
        final ScheduledTask outerTask = warden.getScheduler().runAtFixedRate(this, (final ScheduledTask innerTask) -> {
            // Stop the task, if the warden meets ANY SINGLE ONE of these conditions:
            // - Cannot be controlled by the current thread anymore.
            // - Is no longer a valid entity.
            // - Got persistent. (e.g., via nametag)
            if (!server.isOwnedByCurrentRegion(warden) || !warden.isValid() || !warden.getRemoveWhenFarAway()) {
                innerTask.cancel(); // Implicit NPE for 'innerTask'
                return;
            }

            // Do nothing, if the warden is currently being ticked by itself.
            if (warden.isTicking()) return;

            // Wrap.
            try {
                // Tick the warden.
                Internals.tick(mojangWarden);
            } catch (Throwable t) {
                // Log, cancel task, rethrow. (**ERROR**)
                LOGGER.error("digdown: Unable to tick the warden. (warden: {}, mojangWarden: {}, innerTask: {}})", warden, mojangWarden, innerTask, t);
                innerTask.cancel();
                throw new RuntimeException("digdown: Unable to tick the warden. (warden: " + warden + ", mojangWarden: " + mojangWarden + ", innerTask: " + innerTask + ')', t);
            }
        }, /*retired=*/null, /*initialDelayTicks=*/1L, /*periodTicks=*/1L);

        // Add the task to the ticking map. (and cancel any previous task)
        if (outerTask == null) return;
        final ScheduledTask oldTask = TICKING_WARDENS.put(warden, outerTask);
        if (oldTask == null) return;
        oldTask.cancel();
    }

    /// Processes the removal of a [Warden] from the world.
    ///
    /// Stops and removes a ticking task (if any) from the [#TICKING_WARDENS] map.
    ///
    /// @param event Event to handle
    /// @apiNote Do not call, called by [`@EventHandler`][EventHandler]
    /// @see #TICKING_WARDENS
    /// @see #onEntityAddToWorldEvent(EntityAddToWorldEvent)
    @DoNotCall("Called by @EventHandler")
    @EventHandler(ignoreCancelled = true)
    private void onEntityRemoveFromWorldEvent(final EntityRemoveFromWorldEvent event) {
        // Skip, if the entity is NOT warden.
        if (!(event.getEntity() instanceof final Warden warden)) return; // Implicit NPE for 'event'

        // Remove the task from the ticking map. (and cancel it)
        final ScheduledTask oldTask = TICKING_WARDENS.remove(warden);
        if (oldTask == null) return;
        oldTask.cancel();
    }
}
