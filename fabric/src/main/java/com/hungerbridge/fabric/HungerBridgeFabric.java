package com.hungerbridge.fabric;

import com.hungerbridge.common.BridgeServer;
import com.hungerbridge.common.CommandConstants;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.log.PlatformLogAppender;
import com.hungerbridge.common.security.TokenManager;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;

/**
 * Fabric entry point. Lifecycle hooks come from {@code MinecraftServerMixin} so the mod
 * has no dependency on Fabric API (only Fabric Loader).
 */
public final class HungerBridgeFabric implements DedicatedServerModInitializer {

    private static final Logger LOG = new FabricLoggerAdapter();

    private static BridgeServer bridgeServer;
    private static MinecraftServer mcServer;
    private static PlatformLogAppender logAppender;

    // --- tick statistics (fed by the mixin around MinecraftServer#tickChildren) -----------

    private static final int HB_TICK_SAMPLES = 1_200;
    private static final long[] HB_TICK_NANOS = new long[HB_TICK_SAMPLES];
    private static int tickIndex = 0;
    private static long tickCount = 0;

    private static final double TARGET_MS = 50.0;
    private static double ema20 = TARGET_MS;     // ~1 second
    private static double ema1200 = TARGET_MS;   // ~1 minute
    private static double ema6000 = TARGET_MS;   // ~5 minutes
    private static double ema18000 = TARGET_MS;  // ~15 minutes

    public static synchronized void recordTick(long nanos) {
        HB_TICK_NANOS[tickIndex] = nanos;
        tickIndex = (tickIndex + 1) % HB_TICK_SAMPLES;
        tickCount++;
        // A tick never takes less than the 50 ms tick interval from the game's point of view.
        double ms = Math.max(TARGET_MS, nanos / 1_000_000.0);
        ema20 += (ms - ema20) / 20.0;
        ema1200 += (ms - ema1200) / 1200.0;
        ema6000 += (ms - ema6000) / 6000.0;
        ema18000 += (ms - ema18000) / 18000.0;
    }

    /** Average tick duration (ms) over the last {@code samples} ticks, -1 if none yet. */
    public static synchronized double getAverageTickMs(int samples) {
        long available = Math.min(tickCount, HB_TICK_SAMPLES);
        if (samples <= 0 || available == 0) return -1.0;
        int toRead = (int) Math.min(samples, available);
        long sum = 0L;
        int idx = (tickIndex - 1 + HB_TICK_SAMPLES) % HB_TICK_SAMPLES;
        for (int i = 0; i < toRead; i++) {
            sum += HB_TICK_NANOS[idx];
            idx = (idx - 1 + HB_TICK_SAMPLES) % HB_TICK_SAMPLES;
        }
        return (double) sum / toRead / 1_000_000.0;
    }

    private static double tps(double emaMs) {
        if (tickCount == 0) return -1.0;
        return Math.round(Math.min(20.0, 1000.0 / emaMs) * 10.0) / 10.0;
    }

    public static synchronized double getTps20() { return tps(ema20); }
    public static synchronized double getTps1m() { return tps(ema1200); }
    public static synchronized double getTps5m() { return tps(ema6000); }
    public static synchronized double getTps15m() { return tps(ema18000); }

    // --- lifecycle --------------------------------------------------------------------------

    @Override
    public void onInitializeServer() {
        LOG.log("INFO", "HungerBridge initializing.");
    }

    /** Called by the mixin on the first server tick. */
    public static void onServerStarted(MinecraftServer server) {
        mcServer = server;
        logAppender = PlatformLogAppender.install("HungerBridgeFabricLogAppender");

        Path configDir = server.getFile("config").resolve("HungerBridge");
        Config config = Config.load(configDir, LOG);

        TokenManager tm = new TokenManager(configDir, LOG);
        tm.setDebug(config.isDebug());
        config.setTokenManager(tm);

        config.setPlatform("fabric");
        config.setMinecraftVersion(server.getServerVersion());
        config.setBridgeVersion(FabricLoader.getInstance().getModContainer("hungerbridge")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown"));

        // halt(false) is what the vanilla /stop command uses; safe to call off-thread.
        bridgeServer = new BridgeServer(configDir, config, LOG, new FabricCommandExecutor(server), () -> {
            MinecraftServer s = mcServer;
            if (s != null) s.halt(false);
        });
        bridgeServer.start();
        registerCommands(server);
        LOG.log("INFO", "HungerBridge started (fabric, Minecraft " + config.getMinecraftVersion() + ").");
    }

    private static void registerCommands(MinecraftServer server) {
        var dispatcher = server.getCommands().getDispatcher();
        FabricCommandRegistrar.register(dispatcher, bridgeServer, CommandConstants.ROOT);
        FabricCommandRegistrar.register(dispatcher, bridgeServer, CommandConstants.ALIAS);
    }

    /** Called by the mixin when the server stops. */
    public static void onServerStopping() {
        if (bridgeServer != null) {
            LOG.log("INFO", "HungerBridge stopping...");
            bridgeServer.stop();
            bridgeServer = null;
        }
        PlatformLogAppender.uninstall(logAppender);
        logAppender = null;
        mcServer = null;
    }

    /** Called by the mixin after /reload rebuilt the command dispatcher. */
    public static void onServerReload(MinecraftServer server) {
        if (bridgeServer == null || server == null) return;
        bridgeServer.reloadConfig();
        try {
            registerCommands(server);
            LOG.log("INFO", "HungerBridge reloaded config and re-registered commands.");
        } catch (RuntimeException e) {
            LOG.log("WARN", "Failed to re-register HungerBridge commands after reload: " + e.getMessage());
        }
    }

    public static MinecraftServer getServer() {
        return mcServer;
    }
}
