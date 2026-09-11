package com.hungerbridge.common;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

public final class CommandMessages {

    public static final String HEADER = "Usage: /hungerbridge <reload|token>";

    public static List<String> helpLines() {
        return List.of(
            HEADER,
            "Usage: /hungerbridge token <list|create|rotate|revoke|remove>",
            "Usage: /hungerbridge token create <id> <policyId> [expiry]",
            "Usage: /hungerbridge token rotate <id>",
            "Usage: /hungerbridge token revoke <id>",
            "Usage: /hungerbridge token remove <id>",
            "Note: /hb is an alias for /hungerbridge"
        );
    }

    public static String createdToken(String id, String secret) {
        // include a minimal ANSI color code so interactive consoles show tokens
        return "\u001B[32mCreated token: " + id + ":" + secret + "\u001B[0m";
    }

    public static String rotatedToken(String id, String secret) {
        return "\u001B[33mRotated token: " + id + ":" + secret + "\u001B[0m";
    }

    public static String info(String message) {
        return message;
    }

    public static String success(String message) {
        return message;
    }

    public static String warning(String message) {
        return message;
    }

    public static String error(String message) {
        return message;
    }

    // Format a map of key -> value into human-friendly lines. Nested maps are indented.
    public static List<String> formatKeyValues(Map<String, Object> map) {
        List<String> out = new ArrayList<>();
        if (map == null || map.isEmpty()) return out;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v instanceof Map) {
                out.add(k + ":");
                Map<?, ?> sub = (Map<?, ?>) v;
                for (Map.Entry<?, ?> se : sub.entrySet()) {
                    out.add("  " + String.valueOf(se.getKey()) + ": " + String.valueOf(se.getValue()));
                }
            } else {
                out.add(k + ": " + String.valueOf(v));
            }
        }
        return out;
    }

    // Format a simple table. rows are arrays of column values. headers may be null.
    public static List<String> formatTable(List<String[]> rows, String[] headers) {
        List<String> out = new ArrayList<>();
        if ((rows == null || rows.isEmpty()) && (headers == null || headers.length == 0)) return out;

        int cols = headers != null ? headers.length : (rows.get(0) != null ? rows.get(0).length : 0);
        int[] widths = new int[cols];
        if (headers != null) {
            for (int i = 0; i < cols; i++) widths[i] = Math.max(widths[i], headers[i] != null ? headers[i].length() : 0);
        }
        for (String[] r : rows) {
            for (int i = 0; i < cols; i++) {
                String cell = i < r.length && r[i] != null ? r[i] : "";
                widths[i] = Math.max(widths[i], cell.length());
            }
        }

        // cap widths to reasonable maximum (truncate if necessary)
        int maxColWidth = 40;
        for (int i = 0; i < cols; i++) if (widths[i] > maxColWidth) widths[i] = maxColWidth;

        StringBuilder line = new StringBuilder();
        if (headers != null) {
            line.setLength(0);
            for (int i = 0; i < cols; i++) {
                String h = headers[i] != null ? headers[i] : "";
                line.append(padOrTruncate(h, widths[i]));
                if (i < cols - 1) line.append("  ");
            }
            out.add(line.toString());
            // separator
            line.setLength(0);
            for (int i = 0; i < cols; i++) {
                line.append("-".repeat(Math.max(1, widths[i])));
                if (i < cols - 1) line.append("  ");
            }
            out.add(line.toString());
        }

        for (String[] r : rows) {
            line.setLength(0);
            for (int i = 0; i < cols; i++) {
                String cell = i < r.length && r[i] != null ? r[i] : "";
                line.append(padOrTruncate(cell, widths[i]));
                if (i < cols - 1) line.append("  ");
            }
            out.add(line.toString());
        }

        return out;
    }

    private static String padOrTruncate(String s, int width) {
        if (s.length() > width) return s.substring(0, Math.max(0, width - 3)) + "...";
        return String.format("%1$-" + width + "s", s);
    }

    // Format a list into numbered or bulleted human-friendly lines.
    public static List<String> formatList(List<String> items, boolean numbered) {
        List<String> out = new ArrayList<>();
        if (items == null || items.isEmpty()) return out;
        int i = 1;
        for (String it : items) {
            if (numbered) out.add(String.format("%2d. %s", i++, it)); else out.add("- " + it);
        }
        return out;
    }

    // Single-line status formatter
    public static String formatStatus(String name, Object value) {
        return name + ": " + String.valueOf(value);
    }

    // Provide a short preview of JSON strings for human consumption.
    public static String formatJsonPreview(String json, int maxChars) {
        if (json == null) return "";
        String trimmed = json.trim();
        if (trimmed.length() <= maxChars) return trimmed;
        return trimmed.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private CommandMessages() {}
}
