package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public final class ServerRestartHandler implements HttpHandler {
    private final Config config;
    private final Logger logger;
    private final com.hungerbridge.common.BridgeServer bridgeServer;

    public ServerRestartHandler(Config config, Logger logger, com.hungerbridge.common.BridgeServer bridgeServer) {
        this.config = config;
        this.logger = logger;
        this.bridgeServer = bridgeServer;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            HttpUtil.error(ex, 405, "method_not_allowed", "Use POST", config);
            return;
        }
        if (!HttpUtil.auth(ex, config)) {
            HttpUtil.error(ex, 401, "unauthorized", "Authentication required", config);
            return;
        }
        if (!HttpUtil.checkAcl(ex, config, "server.restart")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to restart the server", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "server.restart")) return;

        JsonObject payload = HttpUtil.readJson(ex);
        boolean delayed = payload != null && payload.has("delay") && payload.get("delay").getAsInt() > 0;
        if (logger != null) logger.log("INFO", "Restart requested via API" + (delayed ? " with delay" : ""));
        HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "restart", true, "delayed", delayed));
    }
}
