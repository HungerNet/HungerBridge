package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.CommandExecutor;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public final class WorldTimeHandler implements HttpHandler {
    private final Config config;
    private final Logger logger;
    private final CommandExecutor executor;

    public WorldTimeHandler(Config config, Logger logger, CommandExecutor executor) {
        this.config = config;
        this.logger = logger;
        this.executor = executor;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            HttpUtil.error(ex, 405, "method_not_allowed", "Use GET", config);
            return;
        }
        if (!HttpUtil.auth(ex, config)) {
            HttpUtil.error(ex, 401, "unauthorized", "Authentication required", config);
            return;
        }
        if (!HttpUtil.checkAcl(ex, config, "world.time")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to access time", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "world.time")) return;

        long time = System.currentTimeMillis() / 1000L;
        JsonObject resp = Json.obj("ok", true, "time", time);
        HttpUtil.writeJson(ex, 200, resp);
    }
}
