package com.hungerbridge.common.log;

import com.google.gson.Gson;
import com.hungerbridge.common.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple append-only JSON audit logger. Each event is stored as one JSON
 * object per line. Avoids storing sensitive token material.
 */
public final class AuditLogger {

    private final Path logsDir;
    private final Logger logger;
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    public AuditLogger(Path configDir, Logger logger) {
        this.logger = logger;
        try {
            Path logs = configDir.resolve("logs");
            if (!Files.exists(logs)) Files.createDirectories(logs);
            this.logsDir = logs;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path currentAuditFile() {
        String name = LocalDate.now().format(DATE) + ".audit.log";
        return logsDir.resolve(name);
    }

    public synchronized void logEvent(String tokenId, String ip, String action, String result, Map<String, Object> extra) {
        try {
            Map<String, Object> obj = new HashMap<>();
            obj.put("timestamp", Instant.now().toString());
            if (tokenId != null) obj.put("token_id", tokenId);
            obj.put("ip", ip);
            obj.put("action", action);
            obj.put("result", result);
            if (extra != null) obj.putAll(extra);

            String line = GSON.toJson(obj) + "\n";
            Path file = currentAuditFile();
            Files.write(file, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.log("WARN", "Failed to write audit log: " + e.getMessage());
        }
    }

    public synchronized void pruneOldLogs(int days) {
        if (days <= 0) return;
        try {
            LocalDate cutoff = LocalDate.now().minusDays(days);
            try (java.util.stream.Stream<Path> s = Files.list(logsDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".audit.log")).forEach(p -> {
                    String name = p.getFileName().toString();
                    try {
                        String datePart = name.substring(0, name.indexOf('.'));
                        LocalDate d = LocalDate.parse(datePart, DATE);
                        if (d.isBefore(cutoff)) {
                            Files.deleteIfExists(p);
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (IOException e) {
            logger.log("WARN", "Failed to prune audit logs: " + e.getMessage());
        }
    }
}
