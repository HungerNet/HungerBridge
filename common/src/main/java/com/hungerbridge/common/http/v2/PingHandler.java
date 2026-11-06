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

/** GET /ping → {@code {"ok":true,"server_time":<epoch ms>}}. */
public final class PingHandler extends ApiHandler {

    public PingHandler(Config config, Logger logger) {
        super(config, logger, "/ping", "GET", Permissions.SERVER_PING);
    }

    @Override
    protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
        HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "server_time", System.currentTimeMillis()));
    }
}
