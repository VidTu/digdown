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

import org.bukkit.entity.Warden;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NullMarked;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/// digdown utilities to access server internals.
///
/// @author VidTu
/// @apiNote Internal use only
@ApiStatus.Internal
@NullMarked
final class Internals {
    /// A method from the `CraftWarden` (an implementation class of API's [Warden])
    /// that gets the Mojang warden instance from the Paper/Bukkit entity instance.
    private static final MethodHandle CRAFT_WARDEN_GET_HANDLE;

    /// A method from Mojang's `Warden` instance that can be used to force-tick it.
    private static final MethodHandle MOJANG_WARDEN_TICK;

    static {
        // Wrap.
        try {
            // Find the internal classes.
            final Class<?> craftWardenClass = Class.forName("org.bukkit.craftbukkit.entity.CraftWarden");
            final Class<?> mojangWardenClass = Class.forName("net.minecraft.world.entity.monster.warden.Warden");

            // Lookup the required internal (but public(!)) methods.
            final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            CRAFT_WARDEN_GET_HANDLE = lookup.findVirtual(craftWardenClass, "getHandle",
                    MethodType.methodType(mojangWardenClass));
            MOJANG_WARDEN_TICK = lookup.findVirtual(mojangWardenClass,
                    "tick", MethodType.methodType(void.class));
        } catch (final Throwable t) {
            // Rethrow.
            throw new RuntimeException("digdown: Unable to initialize internals.", t);
        }
    }

    /// An instance of this class cannot be created.
    ///
    /// @throws AssertionError Always
    /// @deprecated Always throws
    @Deprecated(forRemoval = true)
    @Contract(value = "-> fail", pure = true)
    private Internals() {
        throw new AssertionError("digdown: No instances.");
    }

    /// Converts the Paper (API) warden instance to a Mojang (implementation) instance.
    ///
    /// @param paperWarden Paper (API) warden instance to convert
    /// @return Converted Mojang (implementation) warden instance
    /// @throws Throwable If conversion fails
    @Contract(pure = true)
    static Object paperWardenToMojangWarden(final Warden paperWarden) throws Throwable {
        // Convert.
        return CRAFT_WARDEN_GET_HANDLE.invoke(paperWarden); // Implicit NPE for 'paperWarden'
    }

    /// Ticks the (Mojang) warden.
    ///
    /// @param mojangWarden Mojang (implementation) warden instance to tick
    /// @throws Throwable If ticking fails
    static void tick(final Object mojangWarden) throws Throwable {
        // Tick.
        MOJANG_WARDEN_TICK.invoke(mojangWarden); // Implicit NPE for 'mojangWarden'
    }
}
