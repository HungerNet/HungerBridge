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
            HttpUtil.error(ex, 401, "unauthorized", "Authentication required", config);
            return;
        }
        if (!HttpUtil.checkAcl(ex, config, "stream")) {
            HttpUtil.error(ex, 403, "forbidden", "Token not permitted to access stream", config);
            return;
        }
        if (!HttpUtil.rateLimit(ex, config, "stream")) return;

        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=UTF-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache, no-transform");
        ex.getResponseHeaders().add("Connection", "keep-alive");
        ex.getResponseHeaders().add("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        OutputStream out = ex.getResponseBody();
        LogDistributor.StreamConnection connection = LogDistributor.get().register(out);

        // check for ?history=N query parameter
        String query = ex.getRequestURI().getQuery();
        int historyCount = 0;
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && "history".equalsIgnoreCase(kv[0])) {
                    try {
                        historyCount = Integer.parseInt(kv[1]);
                    } catch (NumberFormatException ignored) {
                        // ignore invalid numbers
                    }
                }
            }
        }
        if (historyCount > 0) {
            LogDistributor.get().sendHistory(connection, historyCount);
        }

        try {
            // Write chunks produced by the LogDistributor from the handler thread.
            while (connection.isActive()) {
                String chunk;
                try {
                    chunk = connection.getQueue().take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (chunk == null || chunk.isEmpty()) {
                    // empty chunk used as wake-up; check active flag
                    continue;
                }
                try {
                    out.write(chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    // client disconnected or write failed
                    break;
                }
            }
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
