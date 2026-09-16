package com.hungerbridge.paper;

import com.hungerbridge.common.Logger;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;

/**
 * Paper-specific logger adapter that mirrors the Fabric logger behavior.
 * Ensures an explicit thread name is applied for the duration of the log call.
 */
public final class PaperLoggerAdapter implements Logger {

    private final org.apache.logging.log4j.Logger logger;

    public PaperLoggerAdapter() {
        this.logger = LogManager.getLogger("HungerBridge");
    }

    public PaperLoggerAdapter(org.apache.logging.log4j.Logger logger) {
        this.logger = logger;
    }

    @Override
    public void log(String level, String thread, String message) {
        String previous = Thread.currentThread().getName();
        try {
            if (thread != null && !thread.isEmpty()) {
                try {
                    Thread.currentThread().setName(thread);
                } catch (Exception ignored) {
                }
            } else {
                try {
                    Thread.currentThread().setName("HungerBridge");
                } catch (Exception ignored) {
                }
            }

            String levelName = level == null ? "INFO" : level.trim();
            if (levelName.isEmpty()) {
                levelName = "INFO";
            }

            Level lvl = Level.getLevel(levelName.toUpperCase());
            if (lvl == null) {
                try {
                    lvl = Level.forName(levelName.toUpperCase(), 350);
                } catch (Exception e) {
                    lvl = Level.INFO;
                }
            }

            String threadNameForMessage = (thread != null && !thread.isEmpty()) ? thread : "HungerBridge";
            String decorated = "[" + threadNameForMessage + "] " + (message == null ? "" : message);
            logger.log(lvl, decorated);
        } finally {
            try {
                Thread.currentThread().setName(previous);
            } catch (Exception ignored) {
            }
        }
    }
}
