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

/**
 * /tps
 * GET, requires a valid HMAC-authenticated token
 * Returns tps metrics and tick_time_ms
 */
public final class TpsHandler implements HttpHandler {

    private final Config config;
    private final Logger logger;
    private final CommandExecutor executor;

    public TpsHandler(Config config, Logger logger, CommandExecutor executor) {
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
        if (!HttpUtil.checkAcl(ex, config, "tps")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to access tps", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "tps")) return;

        JsonObject resp = Json.obj(
                "ok", true,
                "tps", executor.getTps(),
                "tps_1m", executor.getTps1m(),
                "tps_5m", executor.getTps5m(),
                "tps_15m", executor.getTps15m(),
                "tick_time_ms", executor.getTickTimeMs()
        );

        HttpUtil.writeJson(ex, 200, resp);
    }
}
