package com.hungerbridge.common.stream;

import com.google.gson.JsonObject;

/**
 * One console log event as delivered by {@code GET /server/stream} (protocol v2).
 *
 * @param seq       position in this JVM's event sequence (starts at 1, strictly increasing)
 * @param time      epoch milliseconds
 * @param level     level name
 * @param thread    thread name
 * @param logger    logger name
 * @param message   the message as logged (secrets redacted)
 * @param throwable stack trace text, or {@code null}
 * @param line      canonical plain-text rendering, see {@link com.hungerbridge.common.log.LineFormat}
 */
public record LogEvent(long seq, long time, String level, String thread, String logger, String message,
                       String throwable, String line) {

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("seq", seq);
        o.addProperty("time", time);
        o.addProperty("level", level);
        o.addProperty("thread", thread);
        o.addProperty("logger", logger);
        o.addProperty("message", message);
        if (throwable != null) o.addProperty("throwable", throwable);
        o.addProperty("line", line);
        return o;
    }
}
