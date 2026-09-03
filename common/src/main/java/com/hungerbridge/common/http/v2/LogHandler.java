package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * /log
 * POST, requires a valid HMAC-authenticated token
 * Body: { "message": "...", "level": "info" }
 */
public final class LogHandler implements HttpHandler {

    private final Config config;
    private final Logger logger;

    public LogHandler(Config config, Logger logger) {
        this.config = config;
        this.logger = logger;
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
        if (!HttpUtil.checkAcl(ex, config, "log")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to post logs", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "log")) return;

        JsonObject json = HttpUtil.readJson(ex);
        if (json == null || !json.has("message")) {
            HttpUtil.error(ex, 400, "bad_request", "Missing field: message", config);
            return;
        }

        String level = json.has("level") ? json.get("level").getAsString() : "info";
        String msg = json.get("message").getAsString();

        logger.log(level.toUpperCase(), msg);
        HttpUtil.writeJson(ex, 200, Json.obj("ok", true));
    }
}
