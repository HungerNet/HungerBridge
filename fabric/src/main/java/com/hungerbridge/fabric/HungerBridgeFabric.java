package com.hungerbridge.fabric;

import com.hungerbridge.common.BridgeServer;
import com.hungerbridge.common.CommandExecutor;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.security.TokenManager;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class HungerBridgeFabric implements DedicatedServerModInitializer {

    private static final org.slf4j.Logger SLF4J_LOGGER =
            LoggerFactory.getLogger("HungerBridge");

    private static BridgeServer bridgeServer;
    private static MinecraftServer mcServer;
    private static FabricLogAppender logAppender;

    private static final int HB_TICK_SAMPLES = 18_000;
    private static final long[] HB_TICK_NANOS = new long[HB_TICK_SAMPLES];
    private static int HB_TICK_INDEX = 0;
    private static long HB_TICK_COUNT = 0;
    private static boolean HB_TICK_WARMED = false;

    private static final double TARGET_MS = 50.0; // ideal tick time
    private static double ema20 = TARGET_MS;      // ~20-tick window
    private static double ema1200 = TARGET_MS;    // ~1 minute
    private static double ema6000 = TARGET_MS;    // ~5 minutes

    public static synchronized void recordTick(long nanos) {
        double ms = nanos / 1_000_000.0;

        HB_TICK_NANOS[HB_TICK_INDEX] = nanos;
        HB_TICK_INDEX = (HB_TICK_INDEX + 1) % HB_TICK_SAMPLES;
        HB_TICK_COUNT++;
        if (!HB_TICK_WARMED && HB_TICK_COUNT >= HB_TICK_SAMPLES) {
            HB_TICK_WARMED = true;
        }

        // smoothing factors
        double alpha20 = 1.0 / 20.0;
        double alpha1200 = 1.0 / 1200.0;
        double alpha6000 = 1.0 / 6000.0;

        // update EMAs
        ema20 = ema20 + alpha20 * (ms - ema20);
        ema1200 = ema1200 + alpha1200 * (ms - ema1200);
        ema6000 = ema6000 + alpha6000 * (ms - ema6000);
    }

    public static synchronized boolean isTickHistoryWarmed() {
        return HB_TICK_WARMED;
    }

    /**
     * Average tick time (ms) over the last `samples` ticks.
     */
    public static synchronized double getAverageTickMs(int samples) {
        if (samples <= 0) return -1.0;
        long available = Math.min(HB_TICK_COUNT, HB_TICK_SAMPLES);
        if (available == 0) return -1.0;

        int toRead = (int) Math.min(samples, available);
        long sum = 0L;
        int idx = (HB_TICK_INDEX - 1 + HB_TICK_SAMPLES) % HB_TICK_SAMPLES;

        for (int i = 0; i < toRead; i++) {
            sum += HB_TICK_NANOS[idx];
            idx = (idx - 1 + HB_TICK_SAMPLES) % HB_TICK_SAMPLES;
        }

        double avgNanos = (double) sum / toRead;
        return avgNanos / 1_000_000.0;
    }

    private static double clampGameSpeed(double rawTps) {
        if (rawTps <= 0.0) return -1.0;
        return Math.min(20.0, rawTps);
    }

    public static synchronized double getTps20() {
        double raw = 1000.0 / ema20;
        return clampGameSpeed(raw);
    }

    public static synchronized double getTps1m() {
        double raw = 1000.0 / ema1200;
        return clampGameSpeed(raw);
    }

    public static synchronized double getTps5m() {
        double raw = 1000.0 / ema6000;
        return clampGameSpeed(raw);
    }

    @Override
    public void onInitializeServer() {
        SLF4J_LOGGER.info("HungerBridge initializing.");
    }

    // Called by mixin on first server tick
    public static void onServerStarted(MinecraftServer server) {
        SLF4J_LOGGER.info("HungerBridge starting...");

        mcServer = server;

        org.apache.logging.log4j.core.Logger root =
                (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getRootLogger();
        logAppender = new FabricLogAppender();
        logAppender.start();
        root.addAppender(logAppender);

        Logger logger = new FabricLoggerAdapter(SLF4J_LOGGER);

        Path configDir = server.getFile("config").resolve("HungerBridge");
        Config config = Config.load(configDir, logger);

        // initialize token manager for HMAC token support
        TokenManager tm = new TokenManager(configDir, logger);
        config.setTokenManager(tm);

        // initialize audit logger and rate limiter
        com.hungerbridge.common.log.AuditLogger al = new com.hungerbridge.common.log.AuditLogger(configDir, logger);
        com.hungerbridge.common.security.RateLimiter rl = new com.hungerbridge.common.security.RateLimiter(configDir, logger);
        config.setAuditLogger(al);
        config.setRateLimiter(rl);
        // load optional security config (self-probe, ip lists)
        com.hungerbridge.common.security.SecurityConfig sc = com.hungerbridge.common.security.SecurityConfig.load(configDir);
        config.setSecurityConfig(sc);
        com.hungerbridge.common.CommandsConfig cc = com.hungerbridge.common.CommandsConfig.load(configDir);
        config.setCommandsConfig(cc);
        if (sc != null && rl != null) {
            rl.setLimits(sc.tokenRps, sc.tokenBurst, sc.ipRps, sc.ipBurst);
        }

        config.setPlatform("fabric");
        config.setMinecraftVersion(server.getServerVersion());

        String modVersion = FabricLoader.getInstance()
                .getModContainer("hungerbridge")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        config.setBridgeVersion(modVersion);

        CommandExecutor executor = new FabricCommandExecutor(server);

        bridgeServer = new BridgeServer(configDir, config, logger, executor);
        bridgeServer.start();

        // register brigadier /hb command if enabled
        if (cc == null || cc.enableCommands) {
            try {
                var dispatcher = server.getCommands().getDispatcher();
                var hb = net.minecraft.commands.Commands.literal("hb");

                hb.executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("HungerBridge commands: reload status probe audit tokens ip config"), false);
                    return 1;
                });

                hb.then(net.minecraft.commands.Commands.literal("status").executes(ctx -> {
                    com.hungerbridge.common.AdminService admin = bridgeServer.getAdminService();
                    if (admin == null) return 1;
                    ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(admin.getStatus().toString()), false);
                    return 1;
                }));

                hb.then(net.minecraft.commands.Commands.literal("probe").executes(ctx -> {
                    com.hungerbridge.common.AdminService admin = bridgeServer.getAdminService();
                    if (admin == null) return 1;
                    ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(admin.runProbe().toString()), false);
                    return 1;
                }));

                hb.then(net.minecraft.commands.Commands.literal("reload").executes(ctx -> {
                    com.hungerbridge.common.AdminService admin = bridgeServer.getAdminService();
                    if (admin == null) return 1;
                    boolean ok = admin.reloadConfig();
                    ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(ok ? "reloaded" : "reload failed"), false);
                    return 1;
                }));

                hb.then(net.minecraft.commands.Commands.literal("audit").then(
                        net.minecraft.commands.Commands.argument("n", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).executes(ctx -> {
                            int n = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "n");
                            com.hungerbridge.common.AdminService admin = bridgeServer.getAdminService();
                            if (admin == null) return 1;
                            for (String l : admin.getAuditSummary(n)) ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(l), false);
                            return 1;
                        })
                ));

                hb.then(net.minecraft.commands.Commands.literal("config").executes(ctx -> {
                    com.hungerbridge.common.AdminService admin = bridgeServer.getAdminService();
                    if (admin == null) return 1;
                    var cs = admin.getConfigStatus();
                    for (java.util.Map.Entry<String, Object> e : cs.entrySet()) ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(e.getKey() + ": " + String.valueOf(e.getValue())), false);
                    return 1;
                }));

                // tokens subcommands
                var tokens = net.minecraft.commands.Commands.literal("tokens");
                tokens.then(net.minecraft.commands.Commands.literal("list").executes(ctx -> {
                    com.hungerbridge.common.AdminService admin = bridgeServer.getAdminService();
                    if (admin == null) return 1;
                    ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(admin.listTokens().keySet().toString()), false);
                    return 1;
                }));
                tokens.then(net.minecraft.commands.Commands.literal("create").then(
                        net.minecraft.commands.Commands.argument("ttl", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                .executes(ctx -> {
                                    int ttl = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "ttl");
                                    com.hungerbridge.common.AdminService admin = bridgeServer.getAdminService();
                                    if (admin == null) return 1;
                                    var t = admin.createToken(ttl, java.util.List.of(), java.util.List.of());
                                    if (t == null) ctx.getSource().sendFailure(net.minecraft.network.chat.Component.literal("create failed"));
                                    else ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("created: " + t.id + ":" + t.secret), false);
                                    return 1;
                                })
                                .then(net.minecraft.commands.Commands.argument("whitelist", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> {
                                            int ttl = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "ttl");
                                            String wl = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "whitelist");
                                            java.util.List<String> wll = java.util.Arrays.stream(wl.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                                            com.hungerbridge.common.AdminService admin = bridgeServer.getAdminService();
                                            if (admin == null) return 1;
                                            var t = admin.createToken(ttl, wll, java.util.List.of());
                                            if (t == null) ctx.getSource().sendFailure(net.minecraft.network.chat.Component.literal("create failed"));
                                            else ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("created: " + t.id + ":" + t.secret), false);
                                            return 1;
                                        }).then(net.minecraft.commands.Commands.argument("blacklist", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                                            int ttl = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "ttl");
                                            String wl = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "whitelist");
                                            String bl = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "blacklist");
                                            java.util.List<String> wll = java.util.Arrays.stream(wl.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                                            java.util.List<String> bll = java.util.Arrays.stream(bl.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                                            com.hungerbridge.common.AdminService admin = bridgeServer.getAdminService();
                                            if (admin == null) return 1;
                                            var t = admin.createToken(ttl, wll, bll);
                                            if (t == null) ctx.getSource().sendFailure(net.minecraft.network.chat.Component.literal("create failed"));
                                            else ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("created: " + t.id + ":" + t.secret), false);
                                            return 1;
                                        }))
                        )
                );
                tokens.then(net.minecraft.commands.Commands.literal("revoke").then(
                        net.minecraft.commands.Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                            String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id");
                            com.hungerbridge.common.AdminService admin = bridgeServer.getAdminService();
                            if (admin == null) return 1;
                            boolean r = admin.revokeToken(id);
                            ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(r ? "revoked" : "not found"), false);
                            return 1;
                        })
                ));
                tokens.then(net.minecraft.commands.Commands.literal("rotate").then(
                        net.minecraft.commands.Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                            String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id");
                            com.hungerbridge.common.AdminService admin = bridgeServer.getAdminService();
                            if (admin == null) return 1;
                            var t = admin.rotateToken(id);
                            if (t == null) ctx.getSource().sendFailure(net.minecraft.network.chat.Component.literal("rotate failed"));
                            else ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("rotated: " + t.id + ":" + t.secret), false);
                            return 1;
                        })
                ));

                hb.then(tokens);

                dispatcher.register(hb);
            } catch (Exception ignored) {}
        }

        SLF4J_LOGGER.info("HungerBridge started on port {}", config.getPort());
    }

    // Called by mixin on server shutdown
    public static void onServerStopping() {
        if (bridgeServer != null) {
            SLF4J_LOGGER.info("HungerBridge stopping...");
            bridgeServer.stop();
            bridgeServer = null;
        }
        if (logAppender != null) {
            org.apache.logging.log4j.core.Logger root =
                    (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getRootLogger();
            root.removeAppender(logAppender);
            logAppender.stop();
            logAppender = null;
        }

        mcServer = null;
    }

    public static MinecraftServer getServer() {
        return mcServer;
    }
}
