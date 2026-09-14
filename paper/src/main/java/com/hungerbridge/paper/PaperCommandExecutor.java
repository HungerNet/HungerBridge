package com.hungerbridge.paper;

import com.hungerbridge.common.CommandExecutor;
import com.hungerbridge.common.platform.CommandCapture;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class PaperCommandExecutor implements CommandExecutor {

    private final JavaPlugin plugin;
    private final PaperBridgeAdapter bridgeAdapter;

    public PaperCommandExecutor(JavaPlugin plugin) {
        this.plugin = plugin;
        this.bridgeAdapter = new PaperBridgeAdapter(plugin);
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
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return List.of();
        }
    }

    // --- TPS / tick time ---

    @Override
    public double getTps() {
        return getTPS();
    }

    @Override
    public double getTps1m() {
        return callSync(() -> {
            double[] tps = Bukkit.getServer().getTPS();
            if (tps == null || tps.length < 2) return -1.0;
            return tps[1];
        }, -1.0);
    }

    @Override
    public double getTps5m() {
        return callSync(() -> {
            double[] tps = Bukkit.getServer().getTPS();
            if (tps == null || tps.length < 3) return -1.0;
            return tps[2];
        }, -1.0);
    }

    @Override
    public double getTps15m() {
        return callSync(() -> {
            double[] tps = Bukkit.getServer().getTPS();
            if (tps == null || tps.length < 4) return -1.0;
            return tps[3];
        }, -1.0);
    }

    @Override
    public double getTickTimeMs() {
        return callSync(() -> {
            long[] times = Bukkit.getServer().getTickTimes();
            if (times == null || times.length == 0) return -1.0;

            long avg = 0L;
            for (long t : times) avg += t;
            avg /= times.length;

            return avg / 1_000_000.0;
        }, -1.0);
    }

    public double getTPS() {
        return bridgeAdapter.getTPS();
    }

    public CompletableFuture<Double> getTPSAsync() {
        return bridgeAdapter.getTPSAsync();
    }

    public String getWeather() {
        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (world == null) return "clear";
        if (world.isThundering()) return "thunder";
        if (world.hasStorm()) return "rain";
        return "clear";
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

    public List<Map<String, Object>> getOnlinePlayersSafe() {
        return bridgeAdapter.getOnlinePlayersSafe();
    }

    public CompletableFuture<List<Map<String, Object>>> getOnlinePlayersAsync() {
        return bridgeAdapter.getOnlinePlayersAsync();
    }

    public int getMaxPlayersSafe() {
        return bridgeAdapter.getMaxPlayersSafe();
    }

    public CompletableFuture<Integer> getMaxPlayersAsync() {
        return bridgeAdapter.getMaxPlayersAsync();
    }

    @Override
    public Map<String, Integer> getWorldChunkCounts() {
        return bridgeAdapter.getLoadedChunkCountsSync();
    }

    public Map<String, Integer> getWorldChunkCountsSync() {
        return bridgeAdapter.getLoadedChunkCountsSync();
    }

    public CompletableFuture<Map<String, Integer>> getWorldChunkCountsAsync() {
        return bridgeAdapter.getLoadedChunkCountsAsync();
    }

    public Map<String, Integer> getLoadedChunksSync(World world) {
        Map<String, Integer> result = new HashMap<>();
        if (world == null) return result;
        result.put(normalizeWorldKey(world.getName()), bridgeAdapter.getLoadedChunksSync(world).length);
        return result;
    }

    public CompletableFuture<Map<String, Integer>> getLoadedChunksAsync(World world) {
        return bridgeAdapter.getLoadedChunksAsync(world).thenApply(chunks -> {
            Map<String, Integer> result = new HashMap<>();
            if (world != null) {
                result.put(normalizeWorldKey(world.getName()), chunks.length);
            }
            return result;
        });
    }

    @Override
    public Map<String, Integer> getWorldEntityCounts() {
        return bridgeAdapter.getWorldEntityCountsSync();
    }

    public Map<String, Integer> getWorldEntityCountsSync(World world) {
        return bridgeAdapter.getWorldEntityCountsSync(world);
    }

    public CompletableFuture<Map<String, Integer>> getWorldEntityCountsAsync(World world) {
        return bridgeAdapter.getWorldEntityCountsAsync(world);
    }

    private <T> T callSync(java.util.concurrent.Callable<T> task, T fallback) {
        try {
            if (Bukkit.isPrimaryThread()) {
                return task.call();
            }
            Future<T> future = Bukkit.getScheduler().callSyncMethod(plugin, task);
            if (future == null) return fallback;
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (ExecutionException e) {
            return fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String normalizeWorldKey(String worldName) {
        if (worldName == null || worldName.isBlank()) return "world";
        if ("world".equals(worldName)) return "world";
        if ("world_nether".equals(worldName)) return "world_nether";
        if ("world_the_end".equals(worldName)) return "world_the_end";
        return worldName;
    }
}
