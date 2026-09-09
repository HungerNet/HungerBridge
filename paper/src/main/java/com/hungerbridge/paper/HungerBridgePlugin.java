package com.hungerbridge.paper;

import com.hungerbridge.common.BridgeServer;
import com.hungerbridge.common.CommandExecutor;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.log.PlatformLogAppender;
import com.hungerbridge.common.security.TokenManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.apache.logging.log4j.LogManager;

import java.nio.file.Path;

public final class HungerBridgePlugin extends JavaPlugin {

    private BridgeServer bridgeServer;
    private PlatformLogAppender logAppender;
    private static final org.apache.logging.log4j.Logger HB_LOGGER = LogManager.getLogger("HungerBridge");
    private Thread bridgeThread;
    private Logger hbAdapter;

    @Override
    public void onEnable() {
        org.apache.logging.log4j.core.Logger root =
            (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getRootLogger();
        logAppender = new PlatformLogAppender("HungerBridgePaperLogAppender");
        logAppender.start();
        root.addAppender(logAppender);

        Logger logger = (level, thread, message) -> {
            String prev = Thread.currentThread().getName();
            try {
                if (thread != null && !thread.isEmpty()) {
                    try { Thread.currentThread().setName(thread); } catch (Exception ignored) {}
                } else {
                    try { Thread.currentThread().setName("HungerBridge"); } catch (Exception ignored) {}
                }
                org.apache.logging.log4j.Logger raw = org.apache.logging.log4j.LogManager.getLogger("HungerBridge");
                String resolved = level == null ? "INFO" : level.trim();
                org.apache.logging.log4j.Level lvl = org.apache.logging.log4j.Level.getLevel(resolved.toUpperCase());
                if (lvl == null) {
                    try {
                        lvl = org.apache.logging.log4j.Level.forName(resolved.toUpperCase(), 450);
                    } catch (Exception ignored2) {
                        lvl = org.apache.logging.log4j.Level.INFO;
                    }
                }
                raw.log(lvl, message);
            } finally {
                try { Thread.currentThread().setName(prev); } catch (Exception ignored) {}
            }
        };

        Path configDir = getDataFolder().toPath();
        Config config = Config.load(configDir, logger);

        // initialize token manager for HMAC token support
        TokenManager tm = new TokenManager(configDir, logger);
        tm.setDebug(config.isDebug());
        config.setTokenManager(tm);

        // auditing, IP lists and rate limiting removed

        config.setPlatform("paper");
        config.setMinecraftVersion(Bukkit.getVersion());

        CommandExecutor executor = new PaperCommandExecutor(this);

        // NOTE: PaperServerInfoProvider exists but is NOT passed into BridgeServer anymore.
        PaperServerInfoProvider infoProvider = new PaperServerInfoProvider(getServer());

        bridgeServer = new BridgeServer(configDir, config, logger, executor, () -> {
            if (getServer() != null) {
                try {
                    var server = getServer();
                    if (server != null && server.getOnlinePlayers().size() >= 0) {
                        Bukkit.shutdown();
                    }
                } catch (Throwable ignored) {
                    try {
                        Bukkit.shutdown();
                    } catch (Throwable ignored2) {
                        // Some Paper versions provide a narrower Server API; a plain shutdown is the safe fallback.
                    }
                }
            }
        });
        hbAdapter = logger;
        // Start the bridge server on a dedicated thread named "HungerBridge"
        bridgeThread = new Thread(() -> {
            try {
                bridgeServer.start();
            } catch (Throwable t) {
                if (hbAdapter != null) {
                    hbAdapter.log("ERROR", "Bridge server thread terminated with error: " + t.toString());
                } else {
                    HB_LOGGER.error("Bridge server thread terminated with error", t);
                }
                t.printStackTrace();
            }
        }, "HungerBridge");
        bridgeThread.setDaemon(false);
        bridgeThread.start();

        // register in-game admin command (always enabled) via common adapter
        try {
            PaperCommandRegistrar.register(this, bridgeServer);
        } catch (Exception ignored) {}

        if (hbAdapter != null) hbAdapter.log("INFO", "HungerBridge enabled."); else HB_LOGGER.info("HungerBridge enabled.");
    }

    @Override
    public void onDisable() {
        if (bridgeServer != null) {
            bridgeServer.stop();
            bridgeServer = null;
            if (bridgeThread != null) {
                try { bridgeThread.interrupt(); bridgeThread.join(2000); } catch (InterruptedException ignored) {}
                bridgeThread = null;
            }
        }
        if (logAppender != null) {
            org.apache.logging.log4j.core.Logger root =
                    (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getRootLogger();
            root.removeAppender(logAppender);
            logAppender.stop();
            logAppender = null;
        }
        if (hbAdapter != null) hbAdapter.log("INFO", "HungerBridge disabled."); else HB_LOGGER.info("HungerBridge disabled.");
    }
}
