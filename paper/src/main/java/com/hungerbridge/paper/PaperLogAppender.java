package com.hungerbridge.paper;

import com.hungerbridge.common.LogDistributor;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

public final class PaperLogAppender extends AbstractAppender {

    public PaperLogAppender() {
        super(
                "HungerBridgePaperLogAppender",
                null,
                PatternLayout.createDefaultLayout(),
                false,
                Property.EMPTY_ARRAY
        );
    }

    @Override
    public void append(LogEvent event) {
        if (event == null || event.getMessage() == null) {
            return;
        }

        String message = event.getMessage().getFormattedMessage();
        if (message != null && !message.isEmpty()) {
            LogDistributor.get().publish(message);
        }
    }
}
