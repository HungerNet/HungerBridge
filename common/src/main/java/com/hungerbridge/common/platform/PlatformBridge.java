package com.hungerbridge.common.platform;

import java.util.List;
import java.util.Map;

/**
 * Shared server/platform abstraction used by common HTTP handlers and parity checks.
 * Thin platform adapters in fabric/paper implement this without changing public API behavior.
 */
public interface PlatformBridge {
    void runCommand(String command);
    List<String> runCommandCapture(String command, boolean showConsole);
    default List<String> runCommandCapture(String command) {
        return runCommandCapture(command, false);
    }
    default void stopServer() {}
    default void restartServer() {}
    default Map<String, Object> getServerMeta() { return Map.of(); }
    default List<Map<String, Object>> listPlayers() { return List.of(); }
    default boolean kickPlayer(String player, String reason) { return false; }
    default boolean banPlayer(String player, String reason) { return false; }
    default double getTps() { return -1.0; }
    default double getTps1m() { return -1.0; }
    default double getTps5m() { return -1.0; }
    default double getTps15m() { return -1.0; }
    default double getMspt() { return -1.0; }
    default Object getChunks() { return null; }
    default Object getTime() { return null; }
    default Object getWeather() { return null; }
    default Map<String, Object> getSystemMetrics() { return Map.of(); }
}
