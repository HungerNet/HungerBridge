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
 * /v2/run
 * POST, requires X-Auth-Key
 * Body: { "command": "...", "silent": bool, "show_console": bool }
 */
public final class RunHandler implements HttpHandler {

    private final Config config;
    private final Logger logger;
    private final CommandExecutor executor;

    public RunHandler(Config config, Logger logger, CommandExecutor executor) {
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
            HttpUtil.error(ex, 401, "unauthorized", "Invalid X-Auth-Key", config);
            return;
        }
        if (!HttpUtil.checkAcl(ex, config, "run")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to run commands", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "run")) return;

        JsonObject json = HttpUtil.readJson(ex);
        if (json == null || !json.has("command")) {
            HttpUtil.error(ex, 400, "bad_request", "Missing field: command", config);
            return;
        }

        String cmd = json.get("command").getAsString();
        boolean silent = json.has("silent") && json.get("silent").getAsBoolean();
        boolean showConsole = json.has("show_console") && json.get("show_console").getAsBoolean();

        List<String> out = executor.executeWithOutput(cmd, showConsole);

        JsonObject resp = Json.obj("ok", true);
        if (!silent && out != null) {
            resp.add("output", Json.GSON.toJsonTree(out));
        }

        HttpUtil.writeJson(ex, 200, resp);
    }
}
