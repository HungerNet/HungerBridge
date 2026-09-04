package com.hungerbridge.paper;

import com.hungerbridge.common.CommandExecutor;
import com.hungerbridge.common.platform.CommandCapture;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class PaperCommandExecutor implements CommandExecutor {

    private final JavaPlugin plugin;

    public PaperCommandExecutor(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(String command) {
        plugin.getServer().getScheduler().runTask(plugin, () ->
                plugin.getServer().dispatchCommand(
                        plugin.getServer().getConsoleSender(),
                        command
                )
        );
    }

    @Override
    public List<String> executeWithOutput(String command, boolean showConsole) {
        CompletableFuture<List<String>> future = new CompletableFuture<>();

        plugin.getServer().getScheduler().runTask(plugin, () -> future.complete(CommandCapture.capture(() ->
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command), showConsole)));

        try {
            return future.get();
        } catch (Exception e) {
            return List.of();
        }
    }

    // --- TPS / tick time ---

    @Override
    public double getTps() {
        double[] tps = Bukkit.getServer().getTPS();
        if (tps.length == 0) return -1.0;
        return tps[0];
    }

    @Override
    public double getTps1m() {
        double[] tps = Bukkit.getServer().getTPS();
        if (tps.length < 2) return -1.0;
        return tps[1];
    }

    @Override
    public double getTps5m() {
        double[] tps = Bukkit.getServer().getTPS();
        if (tps.length < 3) return -1.0;
        return tps[2];
    }

    @Override
    public double getTps15m() {
        // Paper exposes only 3 values; reuse 5m for 15m to keep schema stable.
        double[] tps = Bukkit.getServer().getTPS();
        if (tps.length < 3) return -1.0;
        return tps[2];
    }

    @Override
    public double getTickTimeMs() {
        long[] times = Bukkit.getServer().getTickTimes();
        if (times == null || times.length == 0) return -1.0;

        long avg = 0L;
        for (long t : times) avg += t;
        avg /= times.length;

        return avg / 1_000_000.0;
    }

    // --- Players ---

    @Override
    public List<String> getOnlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return names;
    }
}
