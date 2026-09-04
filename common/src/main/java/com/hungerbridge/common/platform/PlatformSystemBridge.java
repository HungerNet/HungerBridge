package com.hungerbridge.common.platform;

import java.util.Map;

public interface PlatformSystemBridge {
    default Map<String, Object> getSystemMetrics() { return Map.of(); }
    default Map<String, Object> getServerMeta() { return Map.of(); }
    default Object getLogTail(int lines) { return null; }
}
