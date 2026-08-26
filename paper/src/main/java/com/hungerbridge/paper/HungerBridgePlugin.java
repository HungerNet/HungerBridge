package com.hungerbridge.paper;

import com.hungerbridge.common.BridgeServer;
import com.hungerbridge.common.CommandExecutor;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

public final class HungerBridgePlugin extends JavaPlugin {

    private BridgeServer bridgeServer;
    private PaperLogAppender logAppender;

    @Override
    public void onEnable() {
        org.apache.logging.log4j.core.Logger root =
                (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getRootLogger();
        logAppender = new PaperLogAppender();
        logAppender.start();
        root.addAppender(logAppender);

        Logger logger = (level, message) -> {
            org.apache.logging.log4j.Logger raw =
                    org.apache.logging.log4j.LogManager.getLogger("");

            switch (level.toUpperCase()) {
                case "WARN": raw.warn(message); break;
                case "ERROR": raw.error(message); break;
                case "DEBUG": raw.debug(message); break;
                default: raw.info(message); break;
            }
        };

        Path configDir = getDataFolder().toPath();
        Config config = Config.load(configDir, logger);

        config.setPlatform("paper");
        config.setMinecraftVersion(Bukkit.getVersion());

        CommandExecutor executor = new PaperCommandExecutor(this);

        // NOTE: PaperServerInfoProvider exists but is NOT passed into BridgeServer anymore.
        PaperServerInfoProvider infoProvider = new PaperServerInfoProvider(getServer());

        bridgeServer = new BridgeServer(config, logger, executor);
        bridgeServer.start();

        getLogger().info("HungerBridge enabled.");
    }

    @Override
    public void onDisable() {
        if (bridgeServer != null) {
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
        getLogger().info("HungerBridge disabled.");
    }
}
