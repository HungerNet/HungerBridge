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
 * BridgeServer (v2-only). Registers /v2/* endpoints based on config.
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

        // perform HTTP self-probe if configured: ensures the origin is not directly reachable
        com.hungerbridge.common.security.SecurityConfig sc = config.getSecurityConfig();
        if (sc != null && sc.selfProbe && sc.publicBaseUrl != null && !sc.publicBaseUrl.isBlank()) {
            String probe = sc.publicBaseUrl.trim();
            // ensure http
            if (probe.startsWith("https://")) probe = "http://" + probe.substring(8);
            if (!probe.startsWith("http://")) probe = "http://" + probe;
            // strip trailing slash and append /v2/ping
            if (probe.endsWith("/")) probe = probe.substring(0, probe.length()-1);
            String probeUrl = probe + "/v2/ping";
            java.net.HttpURLConnection conn = null;
            try {
                java.net.URL url = new java.net.URL(probeUrl);
                conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(sc.probeTimeoutMs);
                conn.setReadTimeout(sc.probeTimeoutMs);
                conn.setRequestMethod("GET");
                conn.connect();
                int code = conn.getResponseCode();
                // if we got any response, the origin is reachable — fail closed
                if (code >= 0) {
                    throw new RuntimeException("Origin exposure detected: able to reach " + probeUrl + " over HTTP — aborting startup to avoid proxy bypass.");
                }
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                // unreachable or timed out — safe to proceed
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        try {
            server = HttpServer.create(new InetSocketAddress(config.getPort()), 0);
        } catch (IOException e) {
            throw new RuntimeException("Failed to bind HTTP server", e);
        }

        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);

        // v2 endpoints only
        if (config.isPingEnabled()) {
            server.createContext("/v2/ping", new PingHandler(config, logger));
        }
        if (config.isInfoEnabled()) {
            server.createContext("/v2/info", new InfoHandler(config, logger));
        }
        if (config.isStatusEnabled()) {
            server.createContext("/v2/status", new StatusHandler(config, logger));
        }
        if (config.isRunEnabled()) {
            server.createContext("/v2/run", new RunHandler(config, logger, executor));
        }
        if (config.isLogEnabled()) {
            server.createContext("/v2/log", new LogHandler(config, logger));
        }
        if (config.isStreamLogsEnabled()) {
            server.createContext("/v2/stream/logs", new StreamLogsHandler(config));
        }
        // token management endpoint (requires root X-Auth-Key)
        server.createContext("/v2/tokens", new com.hungerbridge.common.http.v2.TokenHandler(config, logger));
        // admin endpoints (require admin privileges / root key)
        com.hungerbridge.common.CommandsConfig cc = config.getCommandsConfig();
        if (cc == null || cc.enableAdminHttp) {
            AdminService admin = new AdminService(configDir, config, logger, this);
            this.adminService = admin;
            server.createContext("/v2/admin/tokens/list", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "tokens_list"));
            server.createContext("/v2/admin/tokens/create", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "tokens_create"));
            server.createContext("/v2/admin/tokens/revoke", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "tokens_revoke"));
            server.createContext("/v2/admin/tokens/rotate", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "tokens_rotate"));
            server.createContext("/v2/admin/status", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "status"));
            server.createContext("/v2/admin/probe", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "probe"));
            server.createContext("/v2/admin/ip", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "ip"));
            server.createContext("/v2/admin/audit", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "audit"));
            server.createContext("/v2/admin/reload", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "reload"));
        }
        if (config.isTpsEnabled()) {
            server.createContext("/v2/tps", new TpsHandler(config, logger, executor));
        }
        if (config.isPlayersEnabled()) {
            server.createContext("/v2/players", new PlayersHandler(config, logger, executor));
        }

        server.start();
        logger.log("INFO", "HungerBridge HTTP server started on port " + config.getPort());
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
        logger.log("INFO", "HungerBridge HTTP server stopped.");
    }

    public AdminService getAdminService() {
        return adminService;
    }
}
