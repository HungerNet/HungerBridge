package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.BridgeServer;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.ApiHandler;
import com.hungerbridge.common.http.HttpUtil;
import com.hungerbridge.common.security.Permissions;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/** GET /server/meta — platform, versions, port and protocol version. */
public final class MetaHandler extends ApiHandler {

    private final java.util.function.IntSupplier port;

    public MetaHandler(Config config, Logger logger, java.util.function.IntSupplier port) {
        super(config, logger, "/server/meta", "GET", Permissions.SERVER_META);
        this.port = port;
    }

    @Override
    protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
        HttpUtil.writeJson(ex, 200, Json.obj(
                "ok", true,
                "platform", config.getPlatform(),
                "minecraft_version", config.getMinecraftVersion(),
                "bridge_version", config.getVersion(),
                "port", port.getAsInt(),
                "protocol_version", BridgeServer.PROTOCOL_VERSION
        ));
    }
}
