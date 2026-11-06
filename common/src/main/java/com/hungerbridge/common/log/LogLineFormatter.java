package com.hungerbridge.common.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Formats log events for the SSE stream as {@code [HH:mm:ss LEVEL]: message}, the same
 * shape Paper/Fabric consoles print (and what clients parse timestamps from). A thrown
 * exception's stack trace is appended on following lines.
 */
public final class LogLineFormatter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private LogLineFormatter() {}

    public static String format(long epochMillis, String level, String message, Throwable thrown) {
        StringBuilder sb = new StringBuilder(64 + (message == null ? 0 : message.length()));
        sb.append('[').append(TIME.format(Instant.ofEpochMilli(epochMillis))).append(' ')
                .append(level == null || level.isBlank() ? "INFO" : level).append("]: ")
                .append(message == null ? "" : message);
        if (thrown != null) {
            StringWriter sw = new StringWriter();
            thrown.printStackTrace(new PrintWriter(sw));
            String trace = sw.toString();
            sb.append('\n').append(trace.endsWith("\n") ? trace.substring(0, trace.length() - 1) : trace);
        }
        return sb.toString();
    }
}
