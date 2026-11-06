package com.hungerbridge.common.http.v2;

import com.hungerbridge.common.Config;
import com.hungerbridge.common.http.HttpUtil;
import com.hungerbridge.common.security.TokenManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/** Catch-all for unknown paths: JSON 404 instead of the JDK's HTML page. */
public final class NotFoundHandler implements HttpHandler {

    private final Config config;

    public NotFoundHandler(Config config) {
        this.config = config;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        // Drain any request body so keep-alive connections stay usable.
        try (var in = ex.getRequestBody()) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        HttpUtil.error(ex, 404, "not_found", "Unknown endpoint: " + TokenManager.normalizePath(ex.getRequestURI().getPath()), config);
    }
}
