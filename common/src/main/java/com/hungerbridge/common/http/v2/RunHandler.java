package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.AuditLogger;
import com.hungerbridge.common.CommandExecutor;
import com.hungerbridge.common.CommandTimeoutException;
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
 * {@code POST /server/run} — {@code {"command": "...", "show_console": false}} →
 * {@code {"ok": true, "output": ["...", ...]}}.
 *
 * <p>Runs with console permissions on the server thread (Folia: the global region). The
 * output is the command's feedback, captured from a dedicated command source; it is written
 * to the server console only when {@code show_console} is true. A command the server does
 * not run within {@link CommandExecutor#COMMAND_TIMEOUT_MS} is a 504 {@code command_timeout}
 * (it may still run later).
 */
public final class RunHandler extends ApiHandler {

    private final CommandExecutor executor;

    public RunHandler(Config config, Logger logger, CommandExecutor executor) {
        super(config, logger, "/server/run", "POST", Permissions.SERVER_RUN);
        this.executor = executor;
    }

    @Override
    protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
        String command = requireString(body, "command");
        boolean showConsole = optBoolean(body, "show_console", false);
        String tokenId = HttpUtil.authTokenId(ex);
        AuditLogger.logRun(config.getConfigDir(), "/server/run", ex.getRequestMethod(), HttpUtil.remoteIp(ex), tokenId, command, "executing");

        List<String> output;
        try {
            output = executor.run(command, showConsole);
        } catch (CommandTimeoutException e) {
            if (logger != null) logger.warn("Command from token " + tokenId + " timed out: " + e.getMessage());
            HttpUtil.error(ex, 504, "command_timeout", e.getMessage(), config);
            return;
        }
        JsonObject resp = Json.obj("ok", true);
        resp.add("output", Json.GSON.toJsonTree(output));
        HttpUtil.writeJson(ex, 200, resp);
    }
}
