package com.hungerbridge.common.platform;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared command output capture for consistent Fabric/Paper behavior.
 */
public final class CommandCapture {
    private CommandCapture() {}

    public static List<String> capture(Runnable action, boolean showConsole) {
        List<String> lines = new ArrayList<>();
        Logger root = (Logger) LogManager.getRootLogger();
        Map<String, Appender> original = root.getAppenders();

        Appender capture = new AbstractAppender(
                "HungerBridgeCommandCapture",
                null,
                PatternLayout.newBuilder().withPattern("%msg").build(),
                false,
                null
        ) {
            @Override
            public void append(LogEvent event) {
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
        if (!showConsole) {
            synchronized (root) {
                for (Appender app : original.values()) {
                    root.removeAppender(app);
                }
            }
        }
        root.addAppender(capture);

        try {
            action.run();
        } finally {
            root.removeAppender(capture);
            capture.stop();
            if (!showConsole) {
                synchronized (root) {
                    for (Appender app : original.values()) {
                        root.addAppender(app);
                    }
                }
            }
        }

        return List.copyOf(lines);
    }
}
