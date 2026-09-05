package com.hungerbridge.common;

import com.hungerbridge.common.http.v2.InfoHandler;
import com.hungerbridge.common.http.v2.LogHandler;
import com.hungerbridge.common.http.v2.PingHandler;
import com.hungerbridge.common.http.v2.PlayersHandler;
import com.hungerbridge.common.http.v2.RunHandler;
import com.hungerbridge.common.http.v2.StatusHandler;
import com.hungerbridge.common.http.v2.StreamLogsHandler;
import com.hungerbridge.common.http.v2.TpsHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BridgeServer. Registers HTTP endpoints based on config.
 */
public final class BridgeServer {

    private final Path configDir;
    private final Config config;
    private final Logger logger;
    private final CommandExecutor executor;

    private HttpServer server;
    private ExecutorService pool;
    private AdminService adminService;

    public BridgeServer(Path configDir, Config config, Logger logger, CommandExecutor executor) {
        this.configDir = configDir;
        this.config = config;
        this.logger = logger;
        this.executor = executor;
    }

    public synchronized void start() {
        if (server != null) return;

        try {
            server = HttpServer.create(new InetSocketAddress(config.getPort()), 0);
        } catch (IOException e) {
            throw new RuntimeException("Failed to bind HTTP server", e);
        }

        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);

        // endpoints (root-level API)
        java.util.List<String> endpoints = new java.util.ArrayList<>();
        AdminService admin = new AdminService(configDir, config, logger, this);
        this.adminService = admin;

        server.createContext("/ping", new PingHandler(config, logger));
        endpoints.add("/ping");
        server.createContext("/server/ping", new PingHandler(config, logger));
        endpoints.add("/server/ping");
        server.createContext("/auth/check", new com.hungerbridge.common.http.v2.AuthCheckHandler(config));
        endpoints.add("/auth/check");
        server.createContext("/server/run", new RunHandler(config, logger, executor));
        endpoints.add("/server/run");
        server.createContext("/server/run-batch", new com.hungerbridge.common.http.v2.RunBatchHandler(config, logger, executor));
        endpoints.add("/server/run-batch");
        server.createContext("/server/stop", new com.hungerbridge.common.http.v2.ServerStopHandler(config, logger, this));
        endpoints.add("/server/stop");
        server.createContext("/server/restart", new com.hungerbridge.common.http.v2.ServerRestartHandler(config, logger, this));
        endpoints.add("/server/restart");
        server.createContext("/server/log", new LogHandler(config, logger));
        endpoints.add("/server/log");
        server.createContext("/server/meta", new com.hungerbridge.common.http.v2.MetaHandler(config, logger));
        endpoints.add("/server/meta");
        server.createContext("/server/stream", new StreamLogsHandler(config));
        endpoints.add("/server/stream");
        server.createContext("/server/stream/logs", new StreamLogsHandler(config));
        endpoints.add("/server/stream/logs");
        server.createContext("/system/uptime", new com.hungerbridge.common.http.v2.SystemUptimeHandler(config, logger));
        endpoints.add("/system/uptime");
        server.createContext("/system/cpu", new com.hungerbridge.common.http.v2.SystemCpuHandler(config, logger));
        endpoints.add("/system/cpu");
        server.createContext("/system/memory", new com.hungerbridge.common.http.v2.SystemMemoryHandler(config, logger));
        endpoints.add("/system/memory");
        server.createContext("/system/disk", new com.hungerbridge.common.http.v2.SystemDiskHandler(config, logger));
        endpoints.add("/system/disk");
        server.createContext("/players/list", new com.hungerbridge.common.http.v2.PlayersListHandler(config, logger, executor));
        endpoints.add("/players/list");
        server.createContext("/players/kick", new com.hungerbridge.common.http.v2.PlayerKickHandler(config, logger, executor));
        endpoints.add("/players/kick");
        server.createContext("/players/ban", new com.hungerbridge.common.http.v2.PlayerBanHandler(config, logger, executor));
        endpoints.add("/players/ban");
        server.createContext("/world/tps", new com.hungerbridge.common.http.v2.WorldTpsHandler(config, logger, executor));
        endpoints.add("/world/tps");
        server.createContext("/world/mspt", new com.hungerbridge.common.http.v2.WorldMsptHandler(config, logger, executor));
        endpoints.add("/world/mspt");
        server.createContext("/world/chunks", new com.hungerbridge.common.http.v2.WorldChunksHandler(config, logger, executor));
        endpoints.add("/world/chunks");
        server.createContext("/world/time", new com.hungerbridge.common.http.v2.WorldTimeHandler(config, logger, executor));
        endpoints.add("/world/time");
        server.createContext("/world/weather", new com.hungerbridge.common.http.v2.WorldWeatherHandler(config, logger, executor));
        endpoints.add("/world/weather");
        server.createContext("/world/events/join", new com.hungerbridge.common.http.v2.WorldJoinEventHandler(config, logger));
        endpoints.add("/world/events/join");
        server.createContext("/world/events/leave", new com.hungerbridge.common.http.v2.WorldLeaveEventHandler(config, logger));
        endpoints.add("/world/events/leave");
        server.createContext("/world/events/chat", new com.hungerbridge.common.http.v2.WorldChatEventHandler(config, logger));
        endpoints.add("/world/events/chat");
        server.createContext("/tokens/pickup", new com.hungerbridge.common.http.v2.PickupHandler(config));
        endpoints.add("/tokens/pickup/{id}");
        server.createContext("/admin/token/list", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "tokens_list"));
        endpoints.add("/admin/token/list");
        server.createContext("/admin/token/create", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "tokens_create"));
        endpoints.add("/admin/token/create");
        server.createContext("/admin/token/revoke", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "tokens_revoke"));
        endpoints.add("/admin/token/revoke");
        server.createContext("/admin/token/remove", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "tokens_remove"));
        endpoints.add("/admin/token/remove");
        server.createContext("/admin/token/rotate", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "tokens_rotate"));
        endpoints.add("/admin/token/rotate");
        server.createContext("/admin/status", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "status"));
        endpoints.add("/admin/status");
        server.createContext("/admin/reload", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "reload"));
        endpoints.add("/admin/reload");
        server.createContext("/admin/audit", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "audit"));
        endpoints.add("/admin/audit");
        server.createContext("/admin/config/get/main", new com.hungerbridge.common.http.v2.AdminConfigGetHandler(admin, config, "main"));
        endpoints.add("/admin/config/get/main");
        server.createContext("/admin/config/get/security", new com.hungerbridge.common.http.v2.AdminConfigGetHandler(admin, config, "security"));
        endpoints.add("/admin/config/get/security");
        server.createContext("/admin/config/get/tokens", new com.hungerbridge.common.http.v2.AdminConfigGetHandler(admin, config, "tokens"));
        endpoints.add("/admin/config/get/tokens");
        server.createContext("/admin/config/update/main", new com.hungerbridge.common.http.v2.AdminConfigUpdateHandler(admin, config, "main"));
        endpoints.add("/admin/config/update/main");
        server.createContext("/admin/config/update/security", new com.hungerbridge.common.http.v2.AdminConfigUpdateHandler(admin, config, "security"));
        endpoints.add("/admin/config/update/security");
        server.createContext("/admin/config/update/tokens", new com.hungerbridge.common.http.v2.AdminConfigUpdateHandler(admin, config, "tokens"));
        endpoints.add("/admin/config/update/tokens");
        server.createContext("/admin/token/meta", new com.hungerbridge.common.http.v2.AdminTokenMetaHandler(admin, config));
        endpoints.add("/admin/token/meta");
        server.createContext("/admin/audit/purge", new com.hungerbridge.common.http.v2.AdminAuditPurgeHandler(admin, config));
        endpoints.add("/admin/audit/purge");
        server.createContext("/tps", new TpsHandler(config, logger, executor));
        endpoints.add("/tps");
        server.createContext("/players", new PlayersHandler(config, logger, executor));
        endpoints.add("/players");
        server.createContext("/server/info", new InfoHandler(config, logger));
        endpoints.add("/server/info");
        server.createContext("/server/status", new StatusHandler(config, logger));
        endpoints.add("/server/status");

        server.start();
        if (logger != null) logger.log("INFO", "HungerBridge HTTP server started on port " + config.getPort());
        if (logger != null) logger.log("INFO", "Registered endpoints: " + String.join(", ", endpoints));
    }

    public synchronized void stop() {
        LogDistributor.get().close();
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        // shutdown TokenManager sweeper if present
        try {
            if (config != null) {
                com.hungerbridge.common.security.TokenManager tm = config.getTokenManager();
                if (tm != null) tm.shutdown();
            }
        } catch (Exception ignored) {}

        logger.log("INFO", "HungerBridge HTTP server stopped.");
    }

    public AdminService getAdminService() {
        return adminService;
    }

    public Logger getLogger() {
        return logger;
    }
}
