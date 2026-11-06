package com.hungerbridge.fabric;

import com.hungerbridge.common.CommandExecutor;
import com.hungerbridge.common.platform.CommandCapture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Fabric implementation. All game state is read on the server thread (the HTTP API runs
 * on its own threads), with a timeout so a stalled server cannot hang API requests.
 * Uses compiled Minecraft calls only — no reflection on mapped names, which silently
 * failed at runtime (intermediary names on 1.21.11) and reported 0/"world" for everything.
 */
public final class FabricCommandExecutor implements CommandExecutor {

    private static final long COMMAND_TIMEOUT_MS = 5_000L;
    private static final long METRIC_TIMEOUT_MS = 2_000L;
    private static final int CURRENT_SAMPLES = 100;

    private final MinecraftServer server;

    public FabricCommandExecutor(MinecraftServer server) {
        this.server = server;
    }

    private <T> T onServerThread(Supplier<T> task, T fallback, long timeoutMs) {
        try {
            if (server.isSameThread()) return task.get();
            CompletableFuture<T> future = server.submit(task);
            T value = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return value != null ? value : fallback;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private CommandSourceStack console() {
        return server.createCommandSourceStack();
    }

    @Override
    public void execute(String command) {
        server.execute(() -> server.getCommands().performPrefixedCommand(console(), command));
    }

    @Override
    public List<String> executeWithOutput(String command, boolean showConsole) {
        return onServerThread(() -> CommandCapture.capture(
                () -> server.getCommands().performPrefixedCommand(console(), command), showConsole), List.of(), COMMAND_TIMEOUT_MS);
    }

    // --- TPS / MSPT (recorded by MinecraftServerMixin) ------------------------------------

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
        return HungerBridgeFabric.getTps15m();
    }

    @Override
    public double getTickTimeMs() {
        double ms = HungerBridgeFabric.getAverageTickMs(CURRENT_SAMPLES);
        return ms <= 0.0 ? -1.0 : Math.round(ms * 1000.0) / 1000.0;
    }

    // --- world state ------------------------------------------------------------------------

    @Override
    public String getWeather() {
        return onServerThread(() -> {
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            if (overworld == null) return "clear";
            if (overworld.isThundering()) return "thunder";
            if (overworld.isRaining()) return "rain";
            return "clear";
        }, "clear", METRIC_TIMEOUT_MS);
    }

    @Override
    public long getWorldFullTime() {
        return onServerThread(() -> {
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            return overworld == null ? -1L : FabricCompat.dayTime(overworld);
        }, -1L, METRIC_TIMEOUT_MS);
    }

    // --- players ------------------------------------------------------------------------------

    @Override
    public List<String> getOnlinePlayerNames() {
        return onServerThread(() -> {
            List<String> names = new ArrayList<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                names.add(player.getName().getString());
            }
            return names;
        }, List.of(), METRIC_TIMEOUT_MS);
    }

    @Override
    public int getMaxPlayers() {
        return server.getPlayerList().getMaxPlayers();
    }

    // --- chunks / entities -----------------------------------------------------------------

    @Override
    public Map<String, Integer> getWorldChunkCounts() {
        return perLevel(level -> level.getChunkSource().getLoadedChunksCount());
    }

    @Override
    public Map<String, Integer> getWorldEntityCounts() {
        return perLevel(level -> {
            int n = 0;
            for (Entity ignored : level.getAllEntities()) n++;
            return n;
        });
    }

    private Map<String, Integer> perLevel(ToIntFunction<ServerLevel> counter) {
        return onServerThread(() -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (ServerLevel level : server.getAllLevels()) {
                counts.put(levelKey(level), counter.applyAsInt(level));
            }
            return counts;
        }, Map.of(), METRIC_TIMEOUT_MS);
    }

    /** Protocol keys for the vanilla dimensions; other dimensions use their id ("mod:name"). */
    static String levelKey(ServerLevel level) {
        var dim = level.dimension();
        if (dim.equals(Level.OVERWORLD)) return "world";
        if (dim.equals(Level.NETHER)) return "world_nether";
        if (dim.equals(Level.END)) return "world_the_end";
        // ResourceKey#toString() = "ResourceKey[minecraft:dimension / <id>]" on all supported versions
        String s = dim.toString();
        int slash = s.lastIndexOf(" / ");
        return slash >= 0 && s.endsWith("]") ? s.substring(slash + 3, s.length() - 1) : s;
    }
}
