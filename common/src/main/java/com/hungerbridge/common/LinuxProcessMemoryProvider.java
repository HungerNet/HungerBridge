package com.hungerbridge.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LinuxProcessMemoryProvider implements ProcessMemoryProvider {
    private static final Path STATUS = Path.of("/proc/self/status");
    private static final Path STATM = Path.of("/proc/self/statm");

    @Override
    public long getProcessUsedBytes() {
        try {
            if (Files.exists(STATUS)) {
                try (BufferedReader reader = Files.newBufferedReader(STATUS)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("VmRSS:")) {
                            String[] parts = line.trim().split("\\s+");
                            if (parts.length >= 2) {
                                return parseKib(parts[1]) * 1024L;
                            }
                        }
                    }
                }
            }
            if (Files.exists(STATM)) {
                try (BufferedReader reader = Files.newBufferedReader(STATM)) {
                    String line = reader.readLine();
                    if (line != null) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2) {
                            long rssPages = Long.parseLong(parts[1]);
                            return rssPages * 4096L;
                        }
                    }
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // Unsupported Linux procfs or parsing failure; return 0.
        }
        return 0L;
    }

    @Override
    public long getProcessVirtualBytes() {
        try {
            if (Files.exists(STATUS)) {
                try (BufferedReader reader = Files.newBufferedReader(STATUS)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("VmSize:")) {
                            String[] parts = line.trim().split("\\s+");
                            if (parts.length >= 2) {
                                return parseKib(parts[1]) * 1024L;
                            }
                        }
                    }
                }
            }
            if (Files.exists(STATM)) {
                try (BufferedReader reader = Files.newBufferedReader(STATM)) {
                    String line = reader.readLine();
                    if (line != null) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 3) {
                            long totalPages = Long.parseLong(parts[0]);
                            return totalPages * 4096L;
                        }
                    }
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // Unsupported Linux procfs or parsing failure; return 0.
        }
        return 0L;
    }

    private static long parseKib(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
