package com.hungerbridge.fabric;

import net.minecraft.server.level.ServerLevel;

/**
 * Minecraft 26.x implementation of the version seam (selected by the version build script).
 * 26.1 replaced the level day-time counter with world clocks.
 */
final class FabricCompat {

    private FabricCompat() {}

    /** Absolute overworld day-time ticks (overworld clock). */
    static long dayTime(ServerLevel level) {
        return level.getOverworldClockTime();
    }
}