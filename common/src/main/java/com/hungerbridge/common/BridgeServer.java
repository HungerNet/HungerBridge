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

        // perform HTTP self-probe if configured: ensures the origin is not directly reachable
        com.hungerbridge.common.security.SecurityConfig sc = config.getSecurityConfig();
        if (sc != null && sc.selfProbe && sc.publicBaseUrl != null && !sc.publicBaseUrl.isBlank()) {
            String probe = sc.publicBaseUrl.trim();
            // ensure http
            if (probe.startsWith("https://")) probe = "http://" + probe.substring(8);
            if (!probe.startsWith("http://")) probe = "http://" + probe;
            // strip trailing slash and append /ping
            if (probe.endsWith("/")) probe = probe.substring(0, probe.length()-1);
            String probeUrl = probe + "/ping";
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

        // endpoints (root-level API)
        java.util.List<String> endpoints = new java.util.ArrayList<>();
        if (config.isPingEnabled()) {
            server.createContext("/ping", new PingHandler(config, logger));
            endpoints.add("/ping");
        }
        if (config.isInfoEnabled()) {
            server.createContext("/info", new InfoHandler(config, logger));
            endpoints.add("/info");
        }
        if (config.isStatusEnabled()) {
            server.createContext("/status", new StatusHandler(config, logger));
            endpoints.add("/status");
        }
        if (config.isRunEnabled()) {
            server.createContext("/run", new RunHandler(config, logger, executor));
            endpoints.add("/run");
        }
        if (config.isLogEnabled()) {
            server.createContext("/log", new LogHandler(config, logger));
            endpoints.add("/log");
        }
        if (config.isStreamLogsEnabled()) {
            server.createContext("/stream/logs", new StreamLogsHandler(config));
            endpoints.add("/stream/logs");
        }
        // token management endpoint (requires root X-Auth-Key)
        server.createContext("/tokens", new com.hungerbridge.common.http.v2.TokenHandler(config, logger));
        endpoints.add("/tokens");
        // admin endpoints (require admin privileges / root key)
        com.hungerbridge.common.CommandsConfig cc = config.getCommandsConfig();
            if (cc == null || cc.enableAdminHttp) {
            AdminService admin = new AdminService(configDir, config, logger, this);
            this.adminService = admin;
            server.createContext("/admin/tokens/list", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "tokens_list"));
            endpoints.add("/admin/tokens/list");
            server.createContext("/admin/tokens/create", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "tokens_create"));
            endpoints.add("/admin/tokens/create");
            server.createContext("/admin/tokens/revoke", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "tokens_revoke"));
            endpoints.add("/admin/tokens/revoke");
            server.createContext("/admin/tokens/rotate", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "tokens_rotate"));
            endpoints.add("/admin/tokens/rotate");
            server.createContext("/admin/status", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "status"));
            endpoints.add("/admin/status");
            server.createContext("/admin/probe", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "probe"));
            endpoints.add("/admin/probe");
            server.createContext("/admin/ip", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "ip"));
            endpoints.add("/admin/ip");
            server.createContext("/admin/audit", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "audit"));
            endpoints.add("/admin/audit");
            server.createContext("/admin/reload", new com.hungerbridge.common.http.v2.AdminHandler(admin, config, "reload"));
            endpoints.add("/admin/reload");
        }
        if (config.isTpsEnabled()) {
            server.createContext("/tps", new TpsHandler(config, logger, executor));
            endpoints.add("/tps");
        }
        if (config.isPlayersEnabled()) {
            server.createContext("/players", new PlayersHandler(config, logger, executor));
            endpoints.add("/players");
        }

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
        logger.log("INFO", "HungerBridge HTTP server stopped.");
    }

    public AdminService getAdminService() {
        return adminService;
    }
}
