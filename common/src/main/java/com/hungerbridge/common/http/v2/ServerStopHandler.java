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

/** POST /server/stop — stops the Minecraft server (the response is sent first). */
public final class ServerStopHandler extends ApiHandler {

    private final Runnable stopAction;

    public ServerStopHandler(Config config, Logger logger, Runnable stopAction) {
        super(config, logger, "/server/stop", "POST", Permissions.SERVER_STOP);
        this.stopAction = stopAction;
    }

    @Override
    protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
        boolean force = optBoolean(body, "force", false);
        if (logger != null) logger.log("INFO", "Stop requested via API by token " + HttpUtil.authTokenId(ex));
        HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "stopped", true, "force", force));
        if (stopAction != null) stopAction.run();
    }
}
