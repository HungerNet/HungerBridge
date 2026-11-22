package com.hungerbridge.common.log;

import com.hungerbridge.common.Logger;

/**
 * One message to log.
 *
 * @param level   level name ({@code INFO}, {@code WARN}, ... or a custom name), upper case
 * @param thread  thread label shown in the console and the stream; {@code null} = the calling thread
 * @param logger  logger name, shown as {@code [logger]}; never {@code null}
 * @param message the message text
 */
public record LogEntry(String level, String thread, String logger, String message) {

    public LogEntry {
        level = level == null || level.isBlank() ? "INFO" : level.trim().toUpperCase(java.util.Locale.ROOT);
        logger = logger == null || logger.isBlank() ? Logger.NAME : logger;
        message = message == null ? "" : message;
    }

    /** A HungerBridge system message on the calling thread. */
    public static LogEntry of(String level, String message) {
        return new LogEntry(level, null, Logger.NAME, message);
    }
}
