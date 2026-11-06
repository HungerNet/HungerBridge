package com.hungerbridge.paper;

import com.hungerbridge.common.BridgeServer;
import com.hungerbridge.common.CommandExecutor;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.log.PlatformLogAppender;
import com.hungerbridge.common.security.TokenManager;
import com.hungerbridge.paper.scheduler.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

/**
 * Paper / Purpur / Folia entry point. The platform (and threading model) is detected at
 * runtime; see {@link PlatformScheduler}.
 */
public final class HungerBridgePlugin extends JavaPlugin {

    private BridgeServer bridgeServer;
    private PlatformLogAppender logAppender;
    private Logger logger;

    @Override
    public void onEnable() {
        logger = new PaperLoggerAdapter();
        PlatformScheduler scheduler = PlatformScheduler.create(this);

        logAppender = PlatformLogAppender.install("HungerBridgePaperLogAppender");

        Path configDir = getDataFolder().toPath();
        Config config = Config.load(configDir, logger);

        TokenManager tm = new TokenManager(configDir, logger);
        tm.setDebug(config.isDebug());
        config.setTokenManager(tm);

        config.setPlatform(scheduler.platformName());
        config.setMinecraftVersion(normalizeMinecraftVersion(Bukkit.getMinecraftVersion(), Bukkit.getVersion()));
        config.setBridgeVersion(pluginVersion());

        CommandExecutor executor = new PaperCommandExecutor(this, scheduler);

        // /server/stop: Bukkit.shutdown() must run on the main thread (Paper) or the
        // global region thread (Folia), exactly like the vanilla /stop command.
        bridgeServer = new BridgeServer(configDir, config, logger, executor, () -> scheduler.runGlobal(Bukkit::shutdown));
        bridgeServer.start();

        PaperCommandRegistrar.register(this, bridgeServer);

        // Bukkit's `/reload` re-creates plugins; re-read HungerBridge config too.
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onServerCommand(ServerCommandEvent ev) {
                String cmd = ev.getCommand() == null ? "" : ev.getCommand().trim().toLowerCase();
                if ((cmd.equals("reload") || cmd.startsWith("reload ")) && bridgeServer != null) {
                    bridgeServer.reloadConfig();
                }
            }
        }, this);

        logger.log("INFO", "HungerBridge enabled (" + scheduler.platformName() + ", Minecraft " + config.getMinecraftVersion() + ").");
    }

    @SuppressWarnings("deprecation") // getDescription() fallback for servers without plugin meta
    private String pluginVersion() {
        try {
            return getPluginMeta().getVersion();
        } catch (Throwable t) {
            return getDescription().getVersion();
        }
    }

    static String normalizeMinecraftVersion(String version, String rawVersion) {
        if (version != null && !version.isBlank()) {
            return version.trim();
        }
        if (rawVersion == null || rawVersion.isBlank()) {
            return "unknown";
        }
        String candidate = rawVersion.trim();
        int cutoff = candidate.indexOf("Paper");
        if (cutoff >= 0) {
            candidate = candidate.substring(0, cutoff).trim();
        }
        if (candidate.contains(" ")) {
            candidate = candidate.substring(0, candidate.indexOf(' ')).trim();
        }
        return candidate.isBlank() ? "unknown" : candidate;
    }

    @Override
    public void onDisable() {
        if (bridgeServer != null) {
            bridgeServer.stop();
            bridgeServer = null;
        }
        PlatformLogAppender.uninstall(logAppender);
        logAppender = null;
        if (logger != null) logger.log("INFO", "HungerBridge disabled.");
    }
}
