package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.LogDistributor;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.ApiHandler;
import com.hungerbridge.common.http.HttpUtil;
import com.hungerbridge.common.security.Permissions;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * GET /server/stream[?history=N] — Server-Sent Events.
 *
 * <pre>
 * data:&lt;history line&gt;      (up to N, oldest first)
 * :history-end              (always sent, also when N = 0; flushes the response headers)
 * data:&lt;live line&gt;         (one event per log line; \n and \r escaped as "\\n"/"\\r")
 * :keepalive                (every 15 s)
 * </pre>
 */
public final class StreamLogsHandler extends ApiHandler {

    public static final String HISTORY_END = ":history-end\n\n";

    public StreamLogsHandler(Config config, Logger logger) {
        super(config, logger, "/server/stream", "GET", Permissions.SERVER_STREAM);
    }

    @Override
    protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
        // Register before answering so a full server can still reply with a proper 503.
        LogDistributor.StreamConnection connection;
        try {
            connection = LogDistributor.get().register();
        } catch (IllegalStateException e) {
            HttpUtil.error(ex, 503, "too_many_clients", "Too many active log streams", config);
            return;
        }

        try {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
            ex.getResponseHeaders().set("X-Accel-Buffering", "no");
            HttpUtil.markResponded(ex);
            ex.sendResponseHeaders(200, 0);

            int history = parseHistory(ex.getRequestURI().getRawQuery());
            if (history > 0) LogDistributor.get().sendHistory(connection, history);
            connection.write(HISTORY_END);

            OutputStream out = ex.getResponseBody();
            while (connection.isActive()) {
                String chunk;
                try {
                    chunk = connection.getQueue().poll(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (chunk == null || chunk.isEmpty()) {
                    if (connection.isStalled()) break;
                    continue;
                }
                try {
                    out.write(chunk.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    break; // client went away
                }
            }
        } finally {
            LogDistributor.get().unregister(connection);
            ex.close();
        }
    }

    static int parseHistory(String query) {
        if (query == null) return 0;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && "history".equalsIgnoreCase(kv[0])) {
                try {
                    return Math.max(0, Integer.parseInt(kv[1].trim()));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }
}
