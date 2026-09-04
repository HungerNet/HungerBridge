package com.hungerbridge.common;

import com.hungerbridge.common.security.TokenManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CommonCommandHandler {

    public static List<String> handle(BridgeServer bridgeServer, String[] args) {
        List<String> out = new ArrayList<>();
        AdminService admin = bridgeServer.getAdminService();
        if (admin == null) {
            addError(out, bridgeServer, "Admin service is not available.");
            return out;
        }

        if (args == null || args.length == 0) {
            out.add(CommandMessages.HEADER);
            out.add("Use '/hungerbridge help' for more details or '/hungerbridge token' for the token subcommands.");
            return out;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
            out.addAll(CommandMessages.helpLines());
            return out;
        }

        try {
            switch (args[0].toLowerCase()) {
                case "reload":
                    boolean ok = admin.reloadConfig();
                    if (ok) addSuccess(out, bridgeServer, "Config reloaded."); else addError(out, bridgeServer, "Reload failed.");
                    break;
                case "status":
                    out.addAll(CommandMessages.formatKeyValues(admin.getStatus()));
                    break;
                case "audit": {
                    if (args.length >= 2 && args[1].equalsIgnoreCase("help")) {
                        out.add("Audit [n]: show the audit summary; optional n (default 20). ");
                        break;
                    }
                    int n = 20;
                    if (args.length >= 2) try { n = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
                    List<String> lines = admin.getAuditSummary(n);
                    if (lines == null || lines.isEmpty()) {
                        addWarning(out, bridgeServer, "No audit entries found.");
                    } else {
                        out.addAll(CommandMessages.formatList(lines, false));
                    }
                    break;
                }
                case "token":
                case "tokens": {
                    if (args.length < 2) { out.add("Token subcommands: list, create, revoke, rotate"); break; }
                    if (args.length >= 2 && args[1].equalsIgnoreCase("help")) {
                        out.add("Token subcommands: list, create <id> [name] [expiry], revoke <id|name>, rotate <id|name>");
                        out.add("Examples: '/hungerbridge token create admin mytoken', '/hungerbridge token create admin mytoken 3600'");
                        break;
                    }
                    switch (args[1].toLowerCase()) {
                        case "list":
                            Map<String, TokenManager.Token> toks = admin.listTokens();
                            if (toks.isEmpty()) {
                                addWarning(out, bridgeServer, "No tokens found.");
                            } else {
                                List<String> tokenLines = new ArrayList<>();
                                for (TokenManager.Token t : toks.values()) {
                                    if (t.name != null && !t.name.isBlank()) tokenLines.add(t.name + " -> " + t.id);
                                    else tokenLines.add(t.id);
                                }
                                out.addAll(CommandMessages.formatList(tokenLines, false));
                            }
                            break;
                        case "create": {
                            if (args.length < 3 || (args.length >= 3 && args[2].equalsIgnoreCase("help"))) {
                                out.add("Usage: /hungerbridge token create <id> [name] [expiry]");
                                out.add("Example: /hungerbridge token create admin mytoken 3600");
                                break;
                            }

                            String id = args[2];
                            String name = null;
                            long expiry = 0L;
                            if (args.length >= 4) {
                                try { expiry = Long.parseLong(args[3]); } catch (NumberFormatException ignored) { name = args[3]; }
                            }
                            if (args.length >= 5) {
                                try { expiry = Long.parseLong(args[4]); } catch (NumberFormatException ignored) {}
                            }
                            TokenManager.Token t = admin.createToken(id, name, expiry, List.of(), List.of());
                            if (t == null) addError(out, bridgeServer, "Unknown token id/policy or duplicate name: " + id);
                            else {
                                out.add(CommandMessages.createdToken(t.id, t.secret));
                                try { if (bridgeServer != null && bridgeServer.getLogger() != null) bridgeServer.getLogger().log("INFO", "Created token: " + t.id); } catch (Exception ignored) {}
                            }
                            break;
                        }
                        case "revoke": {
                            if (args.length < 3) { out.add("Usage: /hungerbridge token revoke <id>"); break; }
                            boolean r = admin.revokeToken(args[2]);
                            if (r) addSuccess(out, bridgeServer, "Revoked token: " + args[2]); else addWarning(out, bridgeServer, "Token not found: " + args[2]);
                            break;
                        }
                        case "rotate": {
                            if (args.length < 3) { out.add("Usage: /hungerbridge token rotate <id>"); break; }
                            TokenManager.Token t = admin.rotateToken(args[2]);
                            if (t == null) addError(out, bridgeServer, "Rotate failed for token: " + args[2]);
                            else {
                                out.add(CommandMessages.rotatedToken(t.id, t.secret));
                                try { if (bridgeServer != null && bridgeServer.getLogger() != null) bridgeServer.getLogger().log("INFO", "Rotated token: " + t.id); } catch (Exception ignored) {}
                            }
                            break;
                        }
                        default: addError(out, bridgeServer, "Unknown token subcommand.");
                    }
                    break;
                }
                case "ip":
                    if (args.length >= 2 && args[1].equalsIgnoreCase("help")) {
                        out.add("IP: show the whitelist/blacklist status.");
                        break;
                    }
                    out.addAll(CommandMessages.formatKeyValues(admin.getIpStatus()));
                    break;
                case "config":
                    Map<String, Object> cs = admin.getConfigStatus();
                    out.addAll(CommandMessages.formatKeyValues(cs));
                    break;
                default:
                    addError(out, bridgeServer, "Unknown subcommand.");
            }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "An unexpected error occurred." : e.getMessage();
            addError(out, bridgeServer, msg);
            try {
                if (bridgeServer != null) {
                    com.hungerbridge.common.Logger l = bridgeServer.getLogger();
                    if (l != null) l.log("ERROR", "Command handler exception: " + msg);
                }
            } catch (Exception ignored) {
            }
        }

        return out;
    }

    private static void addError(List<String> out, BridgeServer bridgeServer, String message) {
        // Do not add the user-facing error text to `out` to avoid duplicate console/chat lines.
        // Only log at ERROR level so the server log shows the issue once.
        try { if (bridgeServer != null && bridgeServer.getLogger() != null) bridgeServer.getLogger().log("ERROR", message); } catch (Exception ignored) {}
    }

    private static void addWarning(List<String> out, BridgeServer bridgeServer, String message) {
        out.add(CommandMessages.warning(message));
        try { if (bridgeServer != null && bridgeServer.getLogger() != null) bridgeServer.getLogger().log("WARN", message); } catch (Exception ignored) {}
    }

    private static void addSuccess(List<String> out, BridgeServer bridgeServer, String message) {
        out.add(CommandMessages.success(message));
        try { if (bridgeServer != null && bridgeServer.getLogger() != null) bridgeServer.getLogger().log("INFO", message); } catch (Exception ignored) {}
    }

    private CommonCommandHandler() {}
}
