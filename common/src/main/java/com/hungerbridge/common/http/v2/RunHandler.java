package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.AuditLogger;
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
 * POST /server/run — {@code {"command": "...", "silent": bool, "show_console": bool}}.
 * Executes as the console and returns captured output lines unless {@code silent}.
 */
public final class RunHandler extends ApiHandler {

    private final CommandExecutor executor;

    public RunHandler(Config config, Logger logger, CommandExecutor executor) {
        super(config, logger, "/server/run", "POST", Permissions.SERVER_RUN);
        this.executor = executor;
    }

    @Override
    protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
        String cmd = requireString(body, "command");
        boolean silent = optBoolean(body, "silent", false);
        boolean showConsole = optBoolean(body, "show_console", false);
        AuditLogger.logRun(config.getConfigDir(), "/server/run", ex.getRequestMethod(), HttpUtil.remoteIp(ex),
                HttpUtil.authTokenId(ex), cmd, "executing");

        List<String> out = executor.executeWithOutput(cmd, showConsole);
        JsonObject resp = Json.obj("ok", true);
        if (!silent && out != null) {
            resp.add("output", Json.GSON.toJsonTree(out));
        }
        HttpUtil.writeJson(ex, 200, resp);
    }
}
