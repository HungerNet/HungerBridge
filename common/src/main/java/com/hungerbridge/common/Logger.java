package com.hungerbridge.common;

import com.hungerbridge.common.log.LogEntry;

/**
 * HungerBridge's logging interface. Platforms use {@link com.hungerbridge.common.log.BridgeLogger};
 * tests use lambdas.
 *
 * <p>{@link #log(LogEntry)} is the single entry point. The level helpers log as the
 * {@value #NAME} logger on the calling thread; {@link #debug} is only meant for diagnostics
 * that are emitted when {@code debug: true} is set in {@code config.yaml}.
 */
@FunctionalInterface
public interface Logger {

    /** Logger name of HungerBridge's own messages, also the default for API log calls. */
    String NAME = "HungerBridge";

    /** Level name of debug diagnostics (a custom level so it is visible when debug mode is on). */
    String DEBUG_LEVEL = "HBDEBUG";

    void log(LogEntry entry);

    default void info(String message) {
        log(LogEntry.of("INFO", message));
    }

    default void warn(String message) {
        log(LogEntry.of("WARN", message));
    }

    default void error(String message) {
        log(LogEntry.of("ERROR", message));
    }

    default void debug(String message) {
        log(LogEntry.of(DEBUG_LEVEL, message));
    }
}
