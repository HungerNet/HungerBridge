package com.hungerbridge.common.http.v2;

import com.hungerbridge.common.Config;
import com.hungerbridge.common.LogDistributor;
import com.hungerbridge.common.http.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;

/**
 * SSE stream for live server log lines.
 */
public final class StreamLogsHandler implements HttpHandler {

    private final Config config;

    public StreamLogsHandler(Config config) {
        this.config = config;
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

        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=UTF-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache, no-transform");
        ex.getResponseHeaders().add("Connection", "keep-alive");
        ex.getResponseHeaders().add("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        OutputStream out = ex.getResponseBody();
        LogDistributor.StreamConnection connection = LogDistributor.get().register(out);

        try {
            connection.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            LogDistributor.get().unregister(connection);
            try {
                out.close();
            } catch (IOException ignored) {
                // Socket is already closed or the client disconnected.
            }
        }
    }
}
