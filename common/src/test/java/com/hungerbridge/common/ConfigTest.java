package com.hungerbridge.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ConfigTest {

    @Test
    public void readsEnabledEndpointsStreamLogs() throws IOException {
        Path dir = Files.createTempDirectory("hungerbridge-config");
        Path configFile = dir.resolve("config.yaml");
        Files.writeString(configFile, """
                port: 1913
                auth:
                  key: "abc"
                enabled_endpoints:
                  run: true
                  log: true
                  ping: true
                  stream_logs: true
                players:
                  max-list: 10
                """);

        Config config = Config.load(dir, (level, message) -> {
        });

        assertTrue(config.isStreamLogsEnabled());
    }
}
