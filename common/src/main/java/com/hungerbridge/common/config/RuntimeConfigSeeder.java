package com.hungerbridge.common.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RuntimeConfigSeeder {
    private RuntimeConfigSeeder() {}

    public static void seed(Path runtimeConfigDir) throws IOException {
        Files.createDirectories(runtimeConfigDir);
        writeIfMissing(runtimeConfigDir.resolve("config.yaml"), "port: 1913\n\nplayers:\n  max-list: 50\n");
        writeIfMissing(runtimeConfigDir.resolve("security.yaml"), "ip_list:\n  mode: blacklist\n  list: []\n\nrate_limits:\n  token_rps: 5.0\n  token_burst: 10.0\n  ip_rps: 20.0\n  ip_burst: 40.0\n\naudit_retention_days: 14\n");
        writeIfMissing(runtimeConfigDir.resolve("tokens.yaml"), "tokens:\n  - id: admin\n    default_expiry: 0\n    max_skew: -1\n\n    endpoints_mode: blacklist\n    endpoints: []\n\n    commands_mode: blacklist\n    commands: []\n");
    }

    private static void writeIfMissing(Path path, String content) throws IOException {
        if (!Files.exists(path)) {
            Files.writeString(path, content);
        }
    }
}
