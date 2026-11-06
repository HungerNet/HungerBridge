package com.hungerbridge.paper;

import com.hungerbridge.common.CommandExecutor;
import com.hungerbridge.common.platform.CommandCapture;
import com.hungerbridge.paper.scheduler.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Paper / Purpur / Folia implementation. Every access to game state goes through
 * {@link PlatformScheduler}, so the same code is correct on the single-threaded Paper
 * main loop and on Folia's regionized threads. Only Bukkit/Paper API is used (no NMS),
 * which keeps the plugin independent of server internals across Minecraft versions.
 */
public final class PaperCommandExecutor implements CommandExecutor {

    /** Upper bound for any blocking hop onto a server thread. */
    static final long COMMAND_TIMEOUT_MS = 5_000L;
    static final long METRIC_TIMEOUT_MS = 2_000L;

    private final JavaPlugin plugin;
    private final PlatformScheduler scheduler;

    public PaperCommandExecutor(JavaPlugin plugin, PlatformScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
    }

    // --- commands -------------------------------------------------------------------

    @Override
    public void execute(String command) {
        scheduler.runGlobal(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    @Override
    public List<String> executeWithOutput(String command, boolean showConsole) {
        // Console commands must run on the main thread (Paper) / global region (Folia).
        return scheduler.callGlobal(
                () -> CommandCapture.capture(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command), showConsole),
                List.of(),
                COMMAND_TIMEOUT_MS);
    }

    // --- TPS / MSPT -------------------------------------------------------------------

    private World overworld() {
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.get(0);
    }

    /**
     * Server TPS array ({@code [1m, 5m, 15m]}). On Folia TPS is tracked per region, so the
     * value is read from the region that owns the overworld spawn.
     */
    private double[] tpsArray() {
        if (scheduler.isFolia()) {
            return scheduler.callInWorld(overworld(), () -> Bukkit.getServer().getTPS(), null, METRIC_TIMEOUT_MS);
        }
        return scheduler.callGlobal(() -> Bukkit.getServer().getTPS(), null, METRIC_TIMEOUT_MS);
    }

    private static double clampTps(double raw) {
        if (!Double.isFinite(raw) || raw <= 0.0) return -1.0;
        return Math.round(Math.min(20.0, raw) * 10.0) / 10.0;
    }

    private double tpsAt(int index) {
        double[] tps = tpsArray();
        if (tps == null || tps.length <= index) return -1.0;
        return clampTps(tps[index]);
    }

    @Override
    public double getTps() {
        double mspt = averageTickTime();
        if (mspt <= 0.0) return tpsAt(0);
        return clampTps(1000.0 / mspt);
    }

    @Override
    public double getTps1m() {
        return tpsAt(0);
    }

    @Override
    public double getTps5m() {
        return tpsAt(1);
    }

    @Override
    public double getTps15m() {
        return tpsAt(2);
    }

    private double averageTickTime() {
        Double value = scheduler.isFolia()
                ? scheduler.callInWorld(overworld(), () -> Bukkit.getServer().getAverageTickTime(), -1.0, METRIC_TIMEOUT_MS)
                : scheduler.callGlobal(() -> Bukkit.getServer().getAverageTickTime(), -1.0, METRIC_TIMEOUT_MS);
        return value != null && Double.isFinite(value) ? value : -1.0;
    }

    @Override
    public double getTickTimeMs() {
        double avg = averageTickTime();
        return avg > 0.0 ? Math.round(avg * 1000.0) / 1000.0 : -1.0;
    }

    // --- world state ------------------------------------------------------------------

    @Override
    public String getWeather() {
        World world = overworld();
        if (world == null) return "clear";
        return scheduler.callGlobal(() -> world.isThundering() ? "thunder" : world.hasStorm() ? "rain" : "clear", "clear", METRIC_TIMEOUT_MS);
    }

    @Override
    public long getWorldFullTime() {
        World world = overworld();
        if (world == null) return -1L;
        return scheduler.callGlobal(world::getFullTime, -1L, METRIC_TIMEOUT_MS);
    }

    // --- players ----------------------------------------------------------------------

    @Override
    public List<String> getOnlinePlayerNames() {
        // The online-player view is a concurrent snapshot on Paper and Folia.
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return names;
    }

    @Override
    public int getMaxPlayers() {
        return Bukkit.getMaxPlayers();
    }

    // --- chunks / entities --------------------------------------------------------------

    @Override
    public Map<String, Integer> getWorldChunkCounts() {
        return perWorld(World::getChunkCount);
    }

    @Override
    public Map<String, Integer> getWorldEntityCounts() {
        return perWorld(World::getEntityCount);
    }

    private Map<String, Integer> perWorld(ToIntFunction<World> counter) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, World> e : worldKeys().entrySet()) {
            World world = e.getValue();
            Integer value = scheduler.callInWorld(world, () -> counter.applyAsInt(world), -1, METRIC_TIMEOUT_MS);
            counts.put(e.getKey(), value == null ? -1 : value);
        }
        return counts;
    }

    /**
     * Maps worlds to the protocol keys {@code world}, {@code world_nether} and
     * {@code world_the_end} by environment (independent of {@code level-name}); any
     * additional worlds are reported under their own name.
     */
    static Map<String, World> worldKeys() {
        Map<String, World> out = new LinkedHashMap<>();
        for (World world : Bukkit.getWorlds()) {
            if (world == null) continue;
            String key = switch (world.getEnvironment()) {
                case NORMAL -> "world";
                case NETHER -> "world_nether";
                case THE_END -> "world_the_end";
                default -> world.getName();
            };
            if (out.containsKey(key)) key = world.getName();
            out.putIfAbsent(key, world);
        }
        return out;
    }

    JavaPlugin plugin() {
        return plugin;
    }
}
