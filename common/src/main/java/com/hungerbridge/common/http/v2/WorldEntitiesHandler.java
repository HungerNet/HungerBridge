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
import java.util.Map;

public final class WorldEntitiesHandler implements HttpHandler {
    private final Config config;
    private final Logger logger;
    private final CommandExecutor executor;

    public WorldEntitiesHandler(Config config, Logger logger, CommandExecutor executor) {
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
        if (!HttpUtil.checkAcl(ex, config, "world.entities")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to access entities", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "world.entities")) return;

        Map<String, Integer> counts = executor.getWorldEntityCounts();
        int total = 0;
        for (Integer value : counts.values()) {
            total += value == null ? 0 : value;
        }

        JsonObject resp = Json.obj(
                "ok", true,
                "total", total,
                "world", counts.getOrDefault("world", 0),
                "world_nether", counts.getOrDefault("world_nether", 0),
                "world_the_end", counts.getOrDefault("world_the_end", 0)
        );
        HttpUtil.writeJson(ex, 200, resp);
    }
}
