package com.hungerbridge.fabric;

import com.hungerbridge.common.CommandExecutor;
import com.hungerbridge.common.platform.CommandCapture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class FabricCommandExecutor implements CommandExecutor {

    private final MinecraftServer server;

    public FabricCommandExecutor(MinecraftServer server) {
        this.server = server;
    }

    private CommandSourceStack console() {
        return server.createCommandSourceStack();
    }

    @Override
    public void execute(String command) {
        server.execute(() ->
                server.getCommands().performPrefixedCommand(console(), command)
        );
    }

    @Override
    public List<String> executeWithOutput(String command, boolean showConsole) {
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        server.execute(() -> future.complete(CommandCapture.capture(() ->
                server.getCommands().performPrefixedCommand(console(), command), showConsole)));

        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return List.of();
        }
    }


    private static final int CURRENT_SAMPLES = 100; // for tick_time_ms

    @Override
    public double getTps() {
        return HungerBridgeFabric.getTps20();
    }

    @Override
    public double getTps1m() {
        return HungerBridgeFabric.getTps1m();
    }

    @Override
    public double getTps5m() {
        return HungerBridgeFabric.getTps5m();
    }

    @Override
    public double getTps15m() {
        // simple passthrough, will remove method later
        return HungerBridgeFabric.getTps5m();
    }

    @Override
    public double getTickTimeMs() {
        double ms = HungerBridgeFabric.getAverageTickMs(CURRENT_SAMPLES);
        return ms <= 0.0 ? -1.0 : ms;
    }

    // ---------- Players ----------

    @Override
    public List<String> getOnlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Component name = player.getName();
            names.add(name.getString());
        }
        return names;
    }

    @Override
    public Map<String, Integer> getWorldChunkCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            String key = normalizeWorldKey(getDimensionKey(level));
            counts.put(key, getLoadedChunkCount(level));
        }
        return counts;
    }

    @Override
    public Map<String, Integer> getWorldEntityCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            String key = normalizeWorldKey(getDimensionKey(level));
            counts.put(key, getEntityCount(level));
        }
        return counts;
    }

    private static String getDimensionKey(ServerLevel level) {
        try {
            Object dimension = level.dimension();
            Object keyObj = dimension.getClass().getMethod("getRegistryKey").invoke(dimension);
            try {
                Object location = keyObj.getClass().getMethod("location").invoke(keyObj);
                if (location != null) return location.toString();
            } catch (ReflectiveOperationException ignored) {
                // Some older Fabric mappings use different accessors.
            }
            return String.valueOf(keyObj);
        } catch (ReflectiveOperationException ignored) {
            return "minecraft:overworld";
        }
    }

    private static int getLoadedChunkCount(ServerLevel level) {
        try {
            Object chunkSource = level.getChunkSource();
            var method = chunkSource.getClass().getMethod("getLoadedChunks");
            Object value = method.invoke(chunkSource);
            if (value instanceof Number number) return number.intValue();
        } catch (ReflectiveOperationException ignored) {
            // Fall back to the server-chunk-cache map size for older Fabric mappings.
        }
        try {
            Object chunkSource = level.getChunkSource();
            Object chunkMap = chunkSource.getClass().getMethod("getChunkMap").invoke(chunkSource);
            Object size = chunkMap.getClass().getMethod("size").invoke(chunkMap);
            if (size instanceof Number number) return number.intValue();
        } catch (ReflectiveOperationException ignored) {
            // No chunk count available; return zero instead of crashing the API.
        }
        return 0;
    }

    private static int getEntityCount(ServerLevel level) {
        try {
            var method = level.getClass().getMethod("getEntityCount");
            Object value = method.invoke(level);
            if (value instanceof Number number) return number.intValue();
        } catch (ReflectiveOperationException ignored) {
            // Some Fabric versions expose entity counts through a different manager API.
        }
        try {
            Object entityManager = level.getClass().getMethod("getEntityManager").invoke(level);
            var method = entityManager.getClass().getMethod("getEntityCount");
            Object value = method.invoke(entityManager);
            if (value instanceof Number number) return number.intValue();
        } catch (ReflectiveOperationException ignored) {
            // Fall back to zero rather than breaking the HTTP API.
        }
        return 0;
    }

    private static String normalizeWorldKey(String worldKey) {
        if (worldKey == null || worldKey.isBlank()) return "world";
        String key = worldKey.replace("minecraft:", "");
        if ("overworld".equals(key)) return "world";
        if ("the_nether".equals(key)) return "world_nether";
        if ("the_end".equals(key)) return "world_the_end";
        return key;
    }
}
