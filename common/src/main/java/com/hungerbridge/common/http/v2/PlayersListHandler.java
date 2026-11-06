package com.hungerbridge.common.http.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hungerbridge.common.CommandExecutor;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.ApiHandler;
import com.hungerbridge.common.http.HttpUtil;
import com.hungerbridge.common.security.Permissions;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.List;

/**
 * GET /players/list → {@code count} (all online players), {@code max} (server slots) and
 * {@code players} (names, capped at {@code players.max-list} from config.yaml).
 */
public final class PlayersListHandler extends ApiHandler {

    private final CommandExecutor executor;

    public PlayersListHandler(Config config, Logger logger, CommandExecutor executor) {
        super(config, logger, "/players/list", "GET", Permissions.PLAYERS_LIST);
        this.executor = executor;
    }

    @Override
    protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
        List<String> names = executor.getOnlinePlayerNames();
        int limit = Math.max(0, config.getPlayersMaxList());
        JsonArray arr = new JsonArray();
        for (int i = 0; i < names.size() && i < limit; i++) arr.add(names.get(i));
        HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "count", names.size(), "max", executor.getMaxPlayers(), "players", arr));
    }
}
