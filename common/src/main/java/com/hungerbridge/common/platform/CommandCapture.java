package com.hungerbridge.common.platform;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared command output capture for consistent Fabric/Paper behavior.
 */
public final class CommandCapture {
    private static final ThreadLocal<Boolean> CAPTURE_ACTIVE = new ThreadLocal<>();

    private CommandCapture() {}

    public static List<String> capture(Runnable action, boolean showConsole) {
        List<String> lines = new ArrayList<>();
        Logger root = (Logger) LogManager.getRootLogger();

        AbstractAppender capture = new AbstractAppender(
                "HungerBridgeCommandCapture",
                null,
                PatternLayout.newBuilder().withPattern("%msg").build(),
                false,
                null
        ) {
            @Override
            public void append(LogEvent event) {
                if (!Boolean.TRUE.equals(CAPTURE_ACTIVE.get())) return;
                if (event == null || event.getMessage() == null) return;
                String msg = event.getMessage().getFormattedMessage();
                if (msg == null) return;
                String trimmed = msg.trim();
                if (!trimmed.isEmpty()) {
                    synchronized (lines) {
                        lines.add(trimmed);
                    }
                }
            }
        };

        capture.start();
        root.addAppender(capture);
        CAPTURE_ACTIVE.set(Boolean.TRUE);

        try {
            action.run();
        } finally {
            CAPTURE_ACTIVE.remove();
            root.removeAppender(capture);
            capture.stop();
        }

        return List.copyOf(lines);
    }
}
