package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.ApiHandler;
import com.hungerbridge.common.http.HttpUtil;
import com.hungerbridge.common.security.Permissions;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * POST /server/log — {@code {"message": "...", "level": "info", "thread": "optional"}}.
 * {@code level} may be any builtin or custom level name.
 */
public final class LogHandler extends ApiHandler {

    public LogHandler(Config config, Logger logger) {
        super(config, logger, "/server/log", "POST", Permissions.SERVER_LOG);
    }

    @Override
    protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
        if (!body.has("message")) throw new HttpUtil.BadRequestException("Missing field: message");
        String msg = optString(body, "message", "");
        String level = optString(body, "level", "INFO").trim();
        String thread = optString(body, "thread", null);
        if (logger != null) {
            logger.log(level.isEmpty() ? "INFO" : level, thread, msg);
        }
        HttpUtil.writeJson(ex, 200, Json.obj("ok", true));
    }
}
