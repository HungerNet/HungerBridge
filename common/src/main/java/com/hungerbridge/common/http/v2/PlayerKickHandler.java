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

public final class PlayerKickHandler implements HttpHandler {
    private final Config config;
    private final Logger logger;
    private final CommandExecutor executor;

    public PlayerKickHandler(Config config, Logger logger, CommandExecutor executor) {
        this.config = config;
        this.logger = logger;
        this.executor = executor;
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
        if (!HttpUtil.checkAcl(ex, config, "players.kick")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to kick players", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "players.kick")) return;

        JsonObject json = HttpUtil.readJson(ex);
        if (json == null || !json.has("player")) {
            HttpUtil.error(ex, 400, "bad_request", "Missing field: player", config);
            return;
        }

        String player = json.get("player").getAsString();
        String reason = json.has("reason") ? json.get("reason").getAsString() : "Kicked by HungerBridge";
        executor.execute("kick " + player + " " + reason);
        HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "player", player, "reason", reason));
    }
}
