package com.hungerbridge.common.platform;

import java.util.Map;

public interface PlatformWorldBridge {
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
