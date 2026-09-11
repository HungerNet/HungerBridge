package com.hungerbridge.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dedicated audit log for API requests. This file is intentionally written outside
 * the normal global logger so it cannot be silenced by command output capture.
 */
public final class AuditLogger {
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOG_TS = DateTimeFormatter.ISO_INSTANT;

    private AuditLogger() {}

    public static void logRequest(Path configDir, String endpoint, String method, String remoteIp, String tokenId, Map<String, String> params, String result) {
        if (endpoint == null || endpoint.isBlank()) return;
        try {
            Path dir = resolveLogDir(configDir);
            Files.createDirectories(dir);
            String fileName = FILE_DATE.format(Instant.now()) + ".audit.log";
            Path path = dir.resolve(fileName);
            String line = buildLine(endpoint, method, remoteIp, tokenId, params, result);
            try (java.io.BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND, java.nio.file.StandardOpenOption.WRITE)) {
                w.write(line);
                w.newLine();
                w.flush();
            }
        } catch (IOException ignored) {
            // Best effort only: audit logging must never break request handling.
        }
    }

    public static void logRun(Path configDir, String endpoint, String method, String remoteIp, String tokenId, String command, String result) {
        Map<String, String> params = new LinkedHashMap<>();
        if (command != null && !command.isBlank()) params.put("command", redact(command));
        logRequest(configDir, endpoint, method, remoteIp, tokenId, params, result);
    }

    public static String sanitizeParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "{}";
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e == null || e.getKey() == null) continue;
            String key = e.getKey();
            String value = e.getValue() == null ? "" : e.getValue();
            sanitized.put(key, redact(value, key));
        }
        return sanitized.toString();
    }

    private static String buildLine(String endpoint, String method, String remoteIp, String tokenId, Map<String, String> params, String result) {
        String now = LOG_TS.format(Instant.now());
        String safeEndpoint = endpoint == null ? "unknown" : endpoint;
        String safeMethod = method == null ? "" : method;
        String safeRemote = remoteIp == null ? "unknown" : remoteIp;
        String safeToken = tokenId == null ? "unknown" : tokenId;
        String safeResult = result == null ? "unknown" : result;
        return now + " method=" + safeMethod + " endpoint=" + safeEndpoint + " remote_ip=" + safeRemote + " token_id=" + safeToken + " params=" + sanitizeParams(params) + " result=" + safeResult;
    }

    private static Path resolveLogDir(Path configDir) {
        if (configDir != null) return configDir.resolve("logs");
        return Path.of("HungerBridge", "logs");
    }

    private static String redact(String value) {
        return redact(value, null);
    }

    private static String redact(String value, String key) {
        if (value == null) return "";
        String text = value.toString();
        if (key != null) {
            String lower = key.toLowerCase();
            if (lower.contains("secret") || lower.contains("pass") || lower.contains("token") || lower.contains("key") || lower.contains("signature") || lower.contains("password") || lower.contains("cookie") || lower.contains("authorization")) {
                return "[REDACTED]";
            }
        }
        String lower = text.toLowerCase();
        if (lower.contains("passkey") || lower.contains("secret") || lower.contains("token") || lower.contains("signature") || lower.contains("password") || lower.contains("authorization") || lower.contains("cookie")) {
            return "[REDACTED]";
        }
        return text;
    }
}
