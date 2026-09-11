package com.hungerbridge.fabric;

import com.hungerbridge.common.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Level;

/**
 * Adapter that bridges the common Logger interface to Log4J2. Accepts
 * arbitrary level names and optional thread names.
 */
public final class FabricLoggerAdapter implements Logger {

    private final org.apache.logging.log4j.Logger logger;

    public FabricLoggerAdapter() {
        this.logger = LogManager.getLogger("HungerBridge");
    }

    public FabricLoggerAdapter(org.apache.logging.log4j.Logger logger) {
        this.logger = logger;
    }

    @Override
    public void log(String level, String thread, String message) {
        String prev = Thread.currentThread().getName();
        try {
            if (thread != null && !thread.isEmpty()) {
                try { Thread.currentThread().setName(thread); } catch (Exception ignored) {}
            } else {
                try { Thread.currentThread().setName("HungerBridge"); } catch (Exception ignored) {}
            }

            String levelName = level == null ? "INFO" : level.trim();
            if (levelName.isEmpty()) levelName = "INFO";

            Level lvl = Level.getLevel(levelName.toUpperCase());
            if (lvl == null) {
                try {
                    lvl = Level.forName(levelName.toUpperCase(), 350);
                } catch (Exception e) {
                    lvl = Level.INFO;
                }
            }

            logger.log(lvl, message);
        } finally {
            try { Thread.currentThread().setName(prev); } catch (Exception ignored) {}
        }
    }
}
