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
import java.util.List;

/**
 * /players
 * GET, requires X-Auth-Key
 * Returns count and players (limited by config.players.max-list)
 */
public final class PlayersHandler implements HttpHandler {

    private final Config config;
    private final Logger logger;
    private final CommandExecutor executor;

    public PlayersHandler(Config config, Logger logger, CommandExecutor executor) {
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
            HttpUtil.error(ex, 401, "unauthorized", "Invalid X-Auth-Key", config);
            return;
        }
        if (!HttpUtil.checkAcl(ex, config, "players")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to access players list", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "players")) return;

        int max = config.getPlayersMaxList();

        List<String> names = executor.getOnlinePlayerNames();
        int count = names.size();

        if (names.size() > max) {
            names = names.subList(0, max);
        }

        JsonObject resp = Json.obj(
                "ok", true,
                "count", count,
                "players", Json.GSON.toJsonTree(names)
        );

        HttpUtil.writeJson(ex, 200, resp);
    }
}
