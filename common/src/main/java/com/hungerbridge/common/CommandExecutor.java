package com.hungerbridge.common;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Interface for executing server commands from the HTTP bridge.
 * Implemented by platform-specific modules.
 */
public interface CommandExecutor {

    /**
     * Execute a command string on the underlying server.
     */
    void execute(String command);

    /**
     * Execute a command and return output lines.
     *
     * @param command     Command to execute.
     * @param showConsole If true, platform should NOT suppress console output
     *                    for this execution. If false, platform may temporarily
     *                    remove appenders to keep the console clean.
     * @return List of output lines, or null if the platform cannot capture output.
     */
    List<String> executeWithOutput(String command, boolean showConsole);

    /**
     * Execute a command and return output lines, in "silent" mode:
     * platforms are allowed to suppress console output.
     */
    default List<String> executeWithOutput(String command) {
        return executeWithOutput(command, false);
    }

    // --- Optional status hooks (platforms can override) ---

    /**
     * Current TPS (last 20 ticks or equivalent).
     */
    default double getTps() {
        return -1.0;
    }

    /**
     * 1-minute TPS average, if available.
     */
    default double getTps1m() {
        return -1.0;
    }

    /**
     * 5-minute TPS average, if available.
     */
    default double getTps5m() {
        return -1.0;
    }

    /**
     * 15-minute TPS average, if available.
     */
    default double getTps15m() {
        return -1.0;
    }

    /**
     * Average tick time in milliseconds.
     */
    default double getTickTimeMs() {
        return -1.0;
    }

    /**
     * Online player names.
     */
    default List<String> getOnlinePlayerNames() {
        return Collections.emptyList();
    }

    /**
     * Loaded chunk counts by world name.
     */
    default Map<String, Integer> getWorldChunkCounts() {
        return Collections.emptyMap();
    }

    /**
     * Entity counts by world name.
     */
    default Map<String, Integer> getWorldEntityCounts() {
        return Collections.emptyMap();
    }
}
