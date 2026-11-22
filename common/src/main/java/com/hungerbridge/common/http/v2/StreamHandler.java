package com.hungerbridge.common.http.v2;

import com.google.gson.JsonObject;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.Json;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.http.ApiHandler;
import com.hungerbridge.common.http.HttpUtil;
import com.hungerbridge.common.security.Permissions;
import com.hungerbridge.common.stream.LogDistributor;
import com.hungerbridge.common.stream.LogEvent;
import com.hungerbridge.common.stream.StreamConnection;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * {@code GET /server/stream} — Server-Sent Events, protocol v2.
 *
 * <pre>
 * retry: 2000
 *
 * id: &lt;epoch&gt;.&lt;seq&gt;              one "log" event per console event (replay first, then live)
 * event: log
 * data: {"seq":..,"time":..,"level":..,"thread":..,"logger":..,"message":..,"line":..}
 *
 * event: ready                      once, after the replay
 * data: {"epoch":..,"seq":..,"replayed":..,"gap":false}
 *
 * : keepalive                       every 15 s
 * </pre>
 *
 * Start point: {@code Last-Event-ID: <epoch>.<seq>} resumes after that event (an id from
 * another epoch, i.e. before a server restart, replays everything still buffered);
 * otherwise {@code ?history=N} or {@code ?history=all} replays the newest events (default 0).
 */
public final class StreamHandler extends ApiHandler {

    static final String RETRY = "retry: 2000\n\n";
    static final String KEEPALIVE = ": keepalive\n\n";

    public StreamHandler(Config config, Logger logger) {
        super(config, logger, "/server/stream", "GET", Permissions.SERVER_STREAM);
    }

    @Override
    protected void handleAuthorized(HttpExchange ex, JsonObject body) throws IOException {
        LogDistributor.Resume resume = resume(ex.getRequestHeaders().getFirst("Last-Event-ID"), ex.getRequestURI().getRawQuery());
        LogDistributor.Subscription sub;
        try {
            sub = LogDistributor.get().subscribe(resume);
        } catch (LogDistributor.TooManyClientsException e) {
            HttpUtil.error(ex, 503, "too_many_clients", e.getMessage(), config);
            return;
        }
        StreamConnection connection = sub.connection();
        try {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
            ex.getResponseHeaders().set("X-Accel-Buffering", "no");
            HttpUtil.markResponded(ex);
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();

            StringBuilder head = new StringBuilder(RETRY);
            for (LogEvent e : sub.replay()) head.append(frame(e));
            JsonObject ready = Json.obj("epoch", LogDistributor.get().epoch(), "seq", sub.seq(),
                    "replayed", sub.replay().size(), "gap", sub.gap());
            head.append("event: ready\ndata: ").append(Json.stringify(ready)).append("\n\n");
            if (!write(out, head.toString())) return;

            while (connection.isActive()) {
                Object item;
                try {
                    item = connection.next(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (item == null) continue;
                String chunk = item instanceof LogEvent e ? frame(e) : StreamConnection.isKeepalive(item) ? KEEPALIVE : null;
                if (chunk != null && !write(out, chunk)) break;
            }
        } finally {
            connection.close();
            ex.close();
        }
    }

    static String frame(LogEvent e) {
        return "id: " + LogDistributor.get().epoch() + "." + e.seq() + "\nevent: log\ndata: " + Json.stringify(e.toJson()) + "\n\n";
    }

    private static boolean write(OutputStream out, String chunk) {
        try {
            out.write(chunk.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (IOException clientGone) {
            return false;
        }
    }

    /** Parses the start point; malformed values are a 400. */
    static LogDistributor.Resume resume(String lastEventId, String rawQuery) throws HttpUtil.BadRequestException {
        if (lastEventId != null && !lastEventId.isBlank()) {
            String id = lastEventId.trim();
            int dot = id.lastIndexOf('.');
            try {
                if (dot <= 0) throw new NumberFormatException();
                return LogDistributor.Resume.after(id.substring(0, dot), Long.parseLong(id.substring(dot + 1)));
            } catch (NumberFormatException e) {
                throw new HttpUtil.BadRequestException("Last-Event-ID must look like <epoch>.<seq>");
            }
        }
        String history = null;
        if (rawQuery != null) {
            for (String param : rawQuery.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && "history".equals(kv[0])) history = kv[1].trim();
            }
        }
        if (history == null || history.isEmpty()) return LogDistributor.Resume.history(0);
        if ("all".equals(history)) return LogDistributor.Resume.history(LogDistributor.Resume.ALL);
        try {
            int n = Integer.parseInt(history);
            if (n < 0) throw new NumberFormatException();
            return LogDistributor.Resume.history(n);
        } catch (NumberFormatException e) {
            throw new HttpUtil.BadRequestException("history must be a non-negative number or 'all'");
        }
    }
}
