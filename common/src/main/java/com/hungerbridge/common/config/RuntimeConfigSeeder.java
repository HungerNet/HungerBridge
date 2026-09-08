package com.hungerbridge.common.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RuntimeConfigSeeder {
    private RuntimeConfigSeeder() {}

    public static void seed(Path runtimeConfigDir) throws IOException {
        Files.createDirectories(runtimeConfigDir);
        ensureDirectoryPermissions(runtimeConfigDir);
        writeIfMissing(runtimeConfigDir.resolve("config.yaml"), "port: 1913\n\nplayers:\n  max-list: 50\n");
        writeIfMissing(runtimeConfigDir.resolve("security.yaml"), "ip_list:\n  mode: blacklist\n  list: []\n\nrate_limits:\n  token_rps: 5.0\n  token_burst: 10.0\n  ip_rps: 20.0\n  ip_burst: 40.0\n\naudit_retention_days: 14\n");
        writeIfMissing(runtimeConfigDir.resolve("policies.yaml"), "policies:\n  - id: moderator\n    default_expiry: 0\n    max_skew: 300\n    permissions:\n      - ping\n      - server.log\n      - server.stream\n      - server.run\n      - world.*\n      - system.*\n");
    }

    private static void writeIfMissing(Path path, String content) throws IOException {
        if (!Files.exists(path)) {
            Files.writeString(path, content);
        }
    }

    private static void ensureDirectoryPermissions(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try {
            if (!Files.isReadable(dir) || !Files.isWritable(dir) || !Files.isExecutable(dir)) {
                java.nio.file.attribute.PosixFilePermission ownerRead = java.nio.file.attribute.PosixFilePermission.OWNER_READ;
                java.nio.file.attribute.PosixFilePermission ownerWrite = java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
                java.nio.file.attribute.PosixFilePermission ownerExecute = java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
                java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = java.util.EnumSet.of(ownerRead, ownerWrite, ownerExecute);
                Files.setPosixFilePermissions(dir, perms);
            }
        } catch (UnsupportedOperationException ignored) {
            // Filesystem does not support POSIX permissions.
        } catch (IOException ignored) {
            // Best effort; if we cannot set permissions, continue.
        }
    }
}
