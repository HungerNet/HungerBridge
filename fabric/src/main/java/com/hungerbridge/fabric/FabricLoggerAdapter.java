package com.hungerbridge.fabric;

import com.hungerbridge.common.Logger;
import org.slf4j.LoggerFactory;

public final class FabricLoggerAdapter implements Logger {

    private final org.slf4j.Logger logger;

    public FabricLoggerAdapter() {
        this.logger = LoggerFactory.getLogger("HungerBridge");
    }

    public FabricLoggerAdapter(org.slf4j.Logger logger) {
        this.logger = logger;
    }

    @Override
    public void log(String level, String message) {
        String prev = Thread.currentThread().getName();
        try {
            Thread.currentThread().setName("HungerBridge");
            switch (level.toUpperCase()) {
                case "WARN":
                    logger.warn(message);
                    break;
                case "ERROR":
                    logger.error(message);
                    break;
                case "DEBUG":
                    logger.debug(message);
                    break;
                default:
                    logger.info(message);
                    break;
            }
        } finally {
            try { Thread.currentThread().setName(prev); } catch (Exception ignored) {}
        }
    }
}
