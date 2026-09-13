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

public final class WorldWeatherHandler implements HttpHandler {
    private final Config config;
    private final Logger logger;
    private final CommandExecutor executor;

    public WorldWeatherHandler(Config config, Logger logger, CommandExecutor executor) {
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
        if (!HttpUtil.checkAcl(ex, config, "world.weather")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to access weather", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "world.weather")) return;

        String weather = "clear";
        try {
            Object value = executor.getClass().getMethod("getWeather").invoke(executor);
            if (value instanceof String str && !str.isBlank()) {
                weather = str;
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall back to the default clear weather value when no platform weather hook exists.
        }

        JsonObject resp = Json.obj("ok", true, "weather", weather);
        HttpUtil.writeJson(ex, 200, resp);
    }
}
