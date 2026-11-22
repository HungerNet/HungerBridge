package com.hungerbridge.common.log;

import com.hungerbridge.common.stream.LogDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Root-logger appender (Paper, Folia and Fabric all use Log4j 2) that turns every console log
 * event into a stream {@link com.hungerbridge.common.stream.LogEvent}. HungerBridge's own
 * messages carry {@link BridgeLogger#MARKER} and are published by {@link BridgeLogger}
 * itself (with their structured thread/logger), so they are skipped here.
 */
public final class PlatformLogAppender extends AbstractAppender {

    private PlatformLogAppender(String name) {
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
        if (event.getMarker() != null && event.getMarker().isInstanceOf(BridgeLogger.MARKER)) return;
        String message = event.getMessage().getFormattedMessage();
        if (message == null || message.isEmpty()) return;
        LogDistributor.get().publish(event.getTimeMillis(), event.getLevel() == null ? "INFO" : event.getLevel().name(),
                event.getThreadName(), event.getLoggerName(), message, stackTrace(event.getThrown()));
    }

    static String stackTrace(Throwable thrown) {
        if (thrown == null) return null;
        StringWriter sw = new StringWriter();
        thrown.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        return trace.endsWith("\n") ? trace.substring(0, trace.length() - 1).stripTrailing() : trace;
    }
}
