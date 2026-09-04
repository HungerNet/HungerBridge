package com.hungerbridge.common;

import java.util.List;

public final class CommandMessages {

    public static final String HEADER = "HungerBridge Commands: reload, status, audit, token, ip, config";
    public static final String USAGE = "Usage: /hungerbridge <subcommand> [args]";
    public static final String SUBCOMMANDS = "Subcommands: reload, status, audit [n], token, ip, config";
    public static final String TOKENS_SUB = "Tokens Subcommands: list, create <id> [expiry], revoke <id>, rotate <id>";

    public static List<String> helpLines() {
        return List.of(USAGE, SUBCOMMANDS, TOKENS_SUB);
    }

    public static String createdToken(String id, String secret) {
        return "Created token: " + id + " -> " + secret;
    }

    public static String rotatedToken(String id, String secret) {
        return "Rotated token: " + id + " -> " + secret;
    }

    public static String info(String message) {
        return message;
    }

    public static String success(String message) {
        return "Success: " + message;
    }

    public static String warning(String message) {
        return "Warning: " + message;
    }

    public static String error(String message) {
        return "Error: " + message;
    }

    private CommandMessages() {}
}
