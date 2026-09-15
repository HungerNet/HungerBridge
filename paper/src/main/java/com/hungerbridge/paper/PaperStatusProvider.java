package com.hungerbridge.paper;

import com.hungerbridge.common.ServerStatusProvider;
import org.bukkit.Bukkit;

public class PaperStatusProvider implements ServerStatusProvider {

    @Override
    public double getTps() {
        double mspt = Bukkit.getServer().getAverageTickTime();
        if (!Double.isFinite(mspt) || mspt <= 0.0) {
            return -1.0;
        }
        return Math.min(20.0, 1000.0 / mspt);
    }

    @Override
    public double getTps1m() {
        double[] tps = Bukkit.getServer().getTPS();
        if (tps == null || tps.length < 2) return -1.0;
        return Math.min(20.0, tps[1]);
    }

    @Override
    public double getTps5m() {
        double[] tps = Bukkit.getServer().getTPS();
        if (tps == null || tps.length < 3) return -1.0;
        return Math.min(20.0, tps[2]);
    }

    @Override
    public double getTps15m() {
        double[] tps = Bukkit.getServer().getTPS();
        if (tps == null || tps.length < 4) return -1.0;
        return Math.min(20.0, tps[3]);
    }

    @Override
    public double getTickTimeMs() {
        long[] times = Bukkit.getServer().getTickTimes();
        long avg = 0;
        for (long t : times) avg += t;
        avg /= times.length;
        return avg / 1_000_000.0;
    }
}
