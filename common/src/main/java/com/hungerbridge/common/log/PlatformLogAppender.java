package com.hungerbridge.common.log;

import com.hungerbridge.common.LogDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

/**
 * Root-logger appender (Paper, Folia and Fabric all use Log4j 2) that forwards every
 * console log event to {@link LogDistributor} for the SSE stream.
 */
public class PlatformLogAppender extends AbstractAppender {

    public PlatformLogAppender(String name) {
        super(name, null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY);
    }

    /** Creates, starts and attaches an appender to the Log4j root logger. */
    public static PlatformLogAppender install(String name) {
        PlatformLogAppender appender = new PlatformLogAppender(name);
        appender.start();
        ((Logger) LogManager.getRootLogger()).addAppender(appender);
        return appender;
    }

    public static void uninstall(PlatformLogAppender appender) {
        if (appender == null) return;
        ((Logger) LogManager.getRootLogger()).removeAppender(appender);
        appender.stop();
    }

    @Override
    public void append(LogEvent event) {
        if (event == null || event.getMessage() == null) return;
        String message = event.getMessage().getFormattedMessage();
        if (message == null || message.isEmpty()) return;
        String level = event.getLevel() == null ? "INFO" : event.getLevel().name();
        LogDistributor.get().publish(LogLineFormatter.format(event.getTimeMillis(), level, message, event.getThrown()));
    }
}
