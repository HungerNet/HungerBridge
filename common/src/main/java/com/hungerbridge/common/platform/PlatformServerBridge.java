package com.hungerbridge.common.platform;

import java.util.List;
import java.util.Map;

public interface PlatformServerBridge {
    void runCommand(String command);
    List<String> runCommandCapture(String command, boolean showConsole);
    default void stopServer() {}
    default void restartServer() {}
    default Map<String, Object> getServerMeta() { return Map.of(); }
    default String getLogTail(int lines) { return ""; }
    default List<Map<String, Object>> listPlayers() { return List.of(); }
    default boolean kickPlayer(String player, String reason) { return false; }
    default boolean banPlayer(String player, String reason) { return false; }
}
