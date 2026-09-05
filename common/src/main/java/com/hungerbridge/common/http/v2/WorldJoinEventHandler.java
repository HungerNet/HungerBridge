package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public final class WorldJoinEventHandler implements HttpHandler {
    private final Config config;
    private final Logger logger;

    public WorldJoinEventHandler(Config config, Logger logger) {
        this.config = config;
        this.logger = logger;
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
        if (!HttpUtil.checkAcl(ex, config, "world.events.join")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to access join events", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "world.events.join")) return;
        HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "event", "join"));
    }
}
