package com.hungerbridge.fabric;

import net.minecraft.server.level.ServerLevel;

/** Minecraft 1.21.x implementation of the version seam (selected by the version build script). */
final class FabricCompat {

    private FabricCompat() {}

    /** Absolute overworld day-time ticks. */
    static long dayTime(ServerLevel level) {
        return level.getDayTime();
    }
}