package com.hungerbridge.common.http.v2;

import com.google.gson.JsonArray;
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

public final class RunBatchHandler implements HttpHandler {
    private final Config config;
    private final Logger logger;
    private final CommandExecutor executor;

    public RunBatchHandler(Config config, Logger logger, CommandExecutor executor) {
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
        if (!HttpUtil.checkAcl(ex, config, "run")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to run commands", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "run")) return;

        JsonObject json = HttpUtil.readJson(ex);
        if (json == null) {
            HttpUtil.error(ex, 400, "bad_request", "Missing request body", config);
            return;
        }

        JsonArray commands = json.has("commands") ? json.getAsJsonArray("commands") : null;
        if (commands == null || commands.size() == 0) {
            HttpUtil.error(ex, 400, "bad_request", "Missing field: commands", config);
            return;
        }

        JsonArray results = new JsonArray();
        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i).getAsString();
            List<String> output = executor.executeWithOutput(command, false);
            JsonObject item = Json.obj("command", command, "output", Json.GSON.toJsonTree(output == null ? List.of() : output));
            results.add(item);
        }
        HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "results", results));
    }
}
