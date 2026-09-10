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

        // Read raw body for debug (populate cached attribute for HttpUtil.readJson)
        String rawBody = null;
        try {
            byte[] bytes = ex.getRequestBody().readAllBytes();
            rawBody = bytes == null ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (rawBody != null && !rawBody.isEmpty()) {
                ex.setAttribute("hb.request.body", rawBody);
                if (logger != null && config != null && config.isDebug()) logger.log("INFO", "[RAW-BODY] " + rawBody);
            }
        } catch (Exception ignored) {}

        JsonObject json = HttpUtil.readJson(ex);
        if (!HttpUtil.auth(ex, config, json)) {  // pass canonical body into auth
            HttpUtil.error(ex, 401, "unauthorized", "Authentication required", config);
            return;
        }

        if (json == null || !json.has("message")) {
            HttpUtil.error(ex, 400, "bad_request", "Missing field: message", config);
            return;
        }

        String level = json.has("level") ? json.get("level").getAsString() : "info";
        String thread = json.has("thread") ? json.get("thread").getAsString() : null;
        String msg = json.get("message").getAsString();

        if (!HttpUtil.checkAcl(ex, config, "server.log")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to post logs", config);
            return;
        }

        if (!HttpUtil.rateLimit(ex, config, "server.log")) return;

        if (logger != null) {
            logger.log(level == null ? "INFO" : level.trim(), thread, msg);
        }
        HttpUtil.writeJson(ex, 200, Json.obj("ok", true));
    }
}
