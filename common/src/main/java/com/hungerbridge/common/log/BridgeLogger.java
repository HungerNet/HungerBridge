package com.hungerbridge.common.log;

import com.hungerbridge.common.Logger;
import com.hungerbridge.common.stream.LogDistributor;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import java.util.regex.Pattern;

/**
 * The one {@link Logger} implementation used on every loader. Each entry is
 *
 * <ol>
 *   <li>written to the server console through Log4j, as logger {@link LogEntry#logger()},
 *       at {@link LogEntry#level()} (custom names become custom Log4j levels between INFO and
 *       WARN, so they are always shown), on a thread named {@link LogEntry#thread()};</li>
 *   <li>published to the stream as a structured event, identical on every loader.</li>
 * </ol>
 *
 * <p>Console layouts differ per loader: the vanilla/Fabric layout
 * ({@code [HH:mm:ss] [thread/LEVEL]: msg}) has no logger column, so with
 * {@code embedLoggerName} the message is written as {@code [logger] msg}; Paper's layout
 * prints {@code [logger]} itself for plugin loggers.
 *
 * <p>The thread label is applied by renaming the calling thread for the duration of the
 * call: Log4j captures the thread name when the event is created (also for async
 * appenders). This is the approach HungerBridge has used in production on all loaders.
 */
public final class BridgeLogger implements Logger {

    /** Marks events written by HungerBridge, which publishes them to the stream itself. */
    public static final Marker MARKER = MarkerManager.getMarker("HUNGERBRIDGE");

    /** Custom level names: letters, digits and underscores, starting with a letter. */
    public static final Pattern LEVEL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,31}");
    private static final int CUSTOM_LEVEL_PRIORITY = 350; // between WARN (300) and INFO (400)

    private final boolean embedLoggerName;

    public BridgeLogger(boolean embedLoggerName) {
        this.embedLoggerName = embedLoggerName;
    }

    @Override
    public void log(LogEntry entry) {
        Level level = level(entry.level());
        org.apache.logging.log4j.Logger target = LogManager.getLogger(entry.logger());
        if (!target.isEnabled(level, MARKER)) return;
        String text = embedLoggerName ? "[" + entry.logger() + "] " + entry.message() : entry.message();

        Thread current = Thread.currentThread();
        String previous = current.getName();
        String thread = entry.thread() == null ? previous : entry.thread();
        boolean rename = !thread.equals(previous);
        try {
            if (rename) current.setName(thread);
            target.log(level, MARKER, text);
        } finally {
            if (rename) current.setName(previous);
        }
        LogDistributor.get().publish(System.currentTimeMillis(), level.name(), thread, entry.logger(), entry.message(), null);
    }

    /**
     * The Log4j level for {@code name}: a built-in level, or a custom level registered once
     * per name. Callers validate API input against {@link #LEVEL_NAME} first.
     *
     * @throws IllegalArgumentException when {@value #MAX_CUSTOM_LEVELS} custom levels exist
     *                                  already (Log4j levels are global and never removed)
     */
    public static Level level(String name) {
        String upper = name == null || name.isBlank() ? "INFO" : name.trim().toUpperCase(java.util.Locale.ROOT);
        Level existing = Level.getLevel(upper);
        if (existing != null) return existing;
        synchronized (CUSTOM_LEVELS) {
            if (!CUSTOM_LEVELS.contains(upper) && CUSTOM_LEVELS.size() >= MAX_CUSTOM_LEVELS) {
                throw new IllegalArgumentException("Too many custom log levels (" + MAX_CUSTOM_LEVELS + ")");
            }
            CUSTOM_LEVELS.add(upper);
            return Level.forName(upper, CUSTOM_LEVEL_PRIORITY);
        }
    }

    public static final int MAX_CUSTOM_LEVELS = 64;
    private static final java.util.Set<String> CUSTOM_LEVELS = new java.util.HashSet<>();
}
