package com.hungerbridge.common.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RuntimeConfigSeeder {
    private RuntimeConfigSeeder() {}

    public static void seed(Path runtimeConfigDir) throws IOException {
        Files.createDirectories(runtimeConfigDir);
        ensureDirectoryPermissions(runtimeConfigDir);
        writeIfMissing(runtimeConfigDir.resolve("config.yaml"), "# HungerBridge core configuration.\n#\n# port:\n#   TCP port that the HungerBridge HTTP server listens on. Default 1913.\n# players.max-list:\n#   Maximum number of player entries returned by the /players endpoint. This\n#   is a safety bound so very large server populations do not overwhelm clients.\n\nport: 1913\n\nplayers:\n  max-list: 50\n");
        writeIfMissing(runtimeConfigDir.resolve("policies.yaml"), "# Example least-privilege policy set.\n# Add '*' explicitly only when you intentionally want full access.\npolicies:\n  - id: default\n    permissions:\n      - 'server.info'\n      - 'player.list'\n      - 'world.read'\n");
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
