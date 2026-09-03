package com.hungerbridge.common;

import java.util.List;

public final class CommandMessages {

    public static final String HEADER = "HungerBridge commands: reload, status, audit, token, ip, config";
    public static final String USAGE = "Usage: /hungerbridge <subcommand> [args]";
    public static final String SUBCOMMANDS = "Subcommands: reload, status, audit [n], token, ip, config";
    public static final String TOKENS_SUB = "Tokens subcommands: list, create <id> [expiry], revoke <id>, rotate <id>";

    public static List<String> helpLines() {
        return List.of(USAGE, SUBCOMMANDS, TOKENS_SUB);
    }

    public static String createdToken(String id, String secret) {
        return "created: " + id + ":" + secret;
    }

    public static String rotatedToken(String id, String secret) {
        return "rotated: " + id + ":" + secret;
    }

    private CommandMessages() {}
}
