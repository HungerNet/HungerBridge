package com.hungerbridge.common;

/**
 * Simple functional logging interface used by the common module.
 * Implementations are provided by platform-specific modules.
 */
@FunctionalInterface
public interface Logger {

    /**
     * Log a message at the given level and thread name.
     * Implementations should accept arbitrary level strings; platform adapters
     * are responsible for resolving builtin or dynamic levels.
     *
     * @param level     log level (e.g. "INFO", "WARN", "ERROR" or custom)
     * @param thread    optional thread name to use for the log event (may be null)
     * @param message   message to log
     */
    void log(String level, String thread, String message);

    /**
     * Backwards-compatible convenience method for callers that only pass
     * level+message. Delegates to the three-arg log method with a null
     * thread name.
     */
    default void log(String level, String message) {
        log(level, null, message);
    }
}
