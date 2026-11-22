package com.hungerbridge.common.log;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * The canonical HungerBridge line format, identical on every loader:
 *
 * <pre>[HH:mm:ss] [thread/LEVEL]: [logger] message</pre>
 *
 * The {@code [logger] } part is left out for Minecraft's own loggers (their messages carry
 * no logger name on the vanilla console either) and when the message already starts with
 * it. Lines are plain text: ANSI escapes and {@code §} formatting codes are removed. The time
 * is in the server's time zone, like the console.
 */
public final class LineFormat {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern LEGACY_CODES = Pattern.compile("§[0-9A-FK-ORXa-fk-orx]");

    private LineFormat() {}

    public static String format(long epochMillis, String level, String thread, String logger, String message, String throwable) {
        String text = plain(message);
        StringBuilder sb = new StringBuilder(48 + text.length());
        sb.append('[').append(TIME.format(Instant.ofEpochMilli(epochMillis))).append("] [")
                .append(thread == null || thread.isBlank() ? "main" : thread).append('/')
                .append(level == null || level.isBlank() ? "INFO" : level).append("]: ");
        if (showsLoggerName(logger) && !text.startsWith("[" + logger + "]")) {
            sb.append('[').append(logger).append("] ");
        }
        sb.append(text);
        if (throwable != null && !throwable.isEmpty()) sb.append('\n').append(plain(throwable));
        return sb.toString();
    }

    /** False for Minecraft's own loggers and the root logger. */
    static boolean showsLoggerName(String logger) {
        if (logger == null || logger.isBlank()) return false;
        return !(logger.startsWith("net.minecraft.") || logger.startsWith("com.mojang.")
                || logger.equals("Minecraft") || logger.equals("ROOT"));
    }

    /** Removes ANSI escape sequences and legacy {@code §} formatting codes. */
    public static String plain(String text) {
        if (text == null || text.isEmpty()) return "";
        String out = text.indexOf('\u001B') >= 0 ? ANSI.matcher(text).replaceAll("") : text;
        return out.indexOf('§') >= 0 ? LEGACY_CODES.matcher(out).replaceAll("") : out;
    }
}
