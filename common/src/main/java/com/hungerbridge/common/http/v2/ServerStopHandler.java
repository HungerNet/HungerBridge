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

/** {@code POST /server/stop} — stops the Minecraft server like {@code /stop} (the response is sent first). */
public final class ServerStopHandler extends ApiHandler {

    private final Runnable stopAction;

    public ServerStopHandler(Config config, Logger logger, Runnable stopAction) {
        super(config, logger, "/server/stop", "POST", Permissions.SERVER_STOP);
        this.stopAction = stopAction;
    }

    @Override
    protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
        if (logger != null) logger.info("Server stop requested through the API by token " + HttpUtil.authTokenId(ex));
        HttpUtil.writeJson(ex, 200, Json.obj("ok", true, "stopped", true));
        if (stopAction != null) stopAction.run();
    }
}
