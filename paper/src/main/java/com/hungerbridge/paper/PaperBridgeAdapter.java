package com.hungerbridge.paper;

import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import com.google.gson.JsonObject;
import com.hungerbridge.common.Json;

public final class PaperBridgeAdapter {

    private final JavaPlugin plugin;

    public PaperBridgeAdapter(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public Map<String, Integer> getWorldEntityCountsSync() {
        return invokeSync(() -> {
            Map<String, Integer> counts = new HashMap<>();
            for (World world : Bukkit.getWorlds()) {
                if (world == null) continue;
                counts.putAll(getWorldEntityCountsSync(world));
            }
            return counts;
        }, Map.of());
    }

    public CompletableFuture<Map<String, Integer>> getWorldEntityCountsAsync() {
        return callSyncFuture(() -> getWorldEntityCountsSync());
    }

    public Map<String, Integer> getWorldEntityCountsSync(World world) {
        if (world == null) return Map.of();
        int count = invokeSync(() -> world.getEntities().size(), 0);
        Map<String, Integer> result = new HashMap<>();
        result.put(normalizeWorldKey(world.getName()), count);
        return result;
    }

    public CompletableFuture<Map<String, Integer>> getWorldEntityCountsAsync(World world) {
        return callSyncFuture(() -> getWorldEntityCountsSync(world));
    }

    public Chunk[] getLoadedChunksSync(World world) {
        if (world == null) return new Chunk[0];
        return invokeSync(() -> world.getLoadedChunks(), new Chunk[0]);
    }

    public CompletableFuture<Chunk[]> getLoadedChunksAsync(World world) {
        return callSyncFuture(() -> getLoadedChunksSync(world));
    }

    public Map<String, Integer> getLoadedChunkCountsSync() {
        return invokeSync(() -> {
            Map<String, Integer> counts = new HashMap<>();
            for (World world : Bukkit.getWorlds()) {
                if (world == null) continue;
                counts.put(normalizeWorldKey(world.getName()), getLoadedChunksSync(world).length);
            }
            return counts;
        }, Map.of());
    }

    public CompletableFuture<Map<String, Integer>> getLoadedChunkCountsAsync() {
        return callSyncFuture(this::getLoadedChunkCountsSync);
    }

    public List<Map<String, Object>> getOnlinePlayersSafe() {
        return invokeSync(() -> {
            List<Map<String, Object>> players = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player == null) continue;
                Map<String, Object> data = new HashMap<>();
                data.put("uuid", player.getUniqueId().toString());
                data.put("name", player.getName());
                data.put("world", player.getWorld() != null ? player.getWorld().getName() : null);
                data.put("location", player.getLocation() != null ? player.getLocation() : null);
                players.add(data);
            }
            return players;
        }, List.of());
    }

    public CompletableFuture<List<Map<String, Object>>> getOnlinePlayersAsync() {
        return callSyncFuture(this::getOnlinePlayersSafe);
    }

    public int getMaxPlayersSafe() {
        return invokeSync(() -> {
            int maxPlayers = MinecraftServer.getServer().getPlayerList().getMaxPlayers();
            if (maxPlayers > 0) return maxPlayers;
            int fallback = Bukkit.getServer().getMaxPlayers();
            if (fallback > 0) return fallback;
            int online = Bukkit.getOnlinePlayers().size();
            return Math.max(20, online);
        }, 0);
    }

    public CompletableFuture<Integer> getMaxPlayersAsync() {
        return callSyncFuture(this::getMaxPlayersSafe);
    }

    private static double roundOneDecimal(double value) {
        if (!Double.isFinite(value)) return -1.0;
        return Math.round(value * 10.0) / 10.0;
    }

    private static double clampTps(double raw) {
        if (!Double.isFinite(raw) || raw <= 0.0) return -1.0;
        return roundOneDecimal(Math.min(20.0, raw));
    }

    public double getTPS() {
        double tps = invokeSync(() -> {
            double mspt = Bukkit.getServer().getAverageTickTime();
            if (!Double.isFinite(mspt) || mspt <= 0.0) {
                return -1.0;
            }
            double currentTPS = 1000.0 / mspt;
            return clampTps(currentTPS);
        }, -1.0);
        return Double.isFinite(tps) ? tps : -1.0;
    }

    public CompletableFuture<Double> getTPSAsync() {
        return callSyncFuture(this::getTPS);
    }

    public double getTps1m() {
        return invokeSync(() -> {
            double[] tps = Bukkit.getServer().getTPS();
            if (tps == null || tps.length < 3) return -1.0;
            return clampTps(tps[0]);
        }, -1.0);
    }

    public double getTps5m() {
        return invokeSync(() -> {
            double[] tps = Bukkit.getServer().getTPS();
            if (tps == null || tps.length < 3) return -1.0;
            return clampTps(tps[1]);
        }, -1.0);
    }

    public double getTps15m() {
        return invokeSync(() -> {
            double[] tps = Bukkit.getServer().getTPS();
            if (tps == null || tps.length < 3) return 20.0;
            double raw = tps[2];
            if (!Double.isFinite(raw) || raw <= 0.0) return 20.0;
            return roundOneDecimal(Math.min(20.0, raw));
        }, 20.0);
    }

    public double getAverageTickTimeMs() {
        return invokeSync(() -> {
            long[] times = Bukkit.getServer().getTickTimes();
            if (times == null || times.length == 0) return -1.0;
            long total = 0L;
            for (long time : times) total += time;
            return (total / (double) times.length) / 1_000_000.0;
        }, -1.0);
    }

    private <T> T invokeSync(Callable<T> task, T fallback) {
        try {
            if (Bukkit.isPrimaryThread()) {
                return task.call();
            }
            Future<T> future = Bukkit.getScheduler().callSyncMethod(plugin, task);
            if (future == null) {
                return fallback;
            }
            return future.get();
        } catch (Exception e) {
            return fallback;
        }
    }

    private <T> CompletableFuture<T> callSyncFuture(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Future<T> syncFuture = Bukkit.getScheduler().callSyncMethod(plugin, task);
                if (syncFuture == null) {
                    future.complete(task.call());
                    return;
                }
                future.complete(syncFuture.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public static String normalizeWorldKey(String worldName) {
        if (worldName == null || worldName.isBlank()) return "world";
        if ("world".equals(worldName)) return "world";
        if ("world_nether".equals(worldName)) return "world_nether";
        if ("world_the_end".equals(worldName)) return "world_the_end";
        return worldName;
    }

    public JsonObject restartServer() {
        return invokeSync(() -> {
            try {
                try {
                    MinecraftServer mc = MinecraftServer.getServer();
                    if (mc != null) {
                        mc.halt(true);
                        return Json.obj("ok", true, "platform", "paper", "restarted", true, "error", null);
                    }
                } catch (NoSuchMethodError | NoClassDefFoundError ignored) {
                    // fall through to dispatch command
                }
                // fallback: dispatch 'restart' command from console
                Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "restart");
                return Json.obj("ok", true, "platform", "paper", "restarted", true, "error", null);
            } catch (Throwable t) {
                return Json.obj("ok", false, "platform", "paper", "restarted", false, "error", t.getMessage());
            }
        }, Json.obj("ok", false, "platform", "paper", "restarted", false, "error", "restart_failed"));
    }
}
