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
            out.add("Admin service not available");
            return out;
        }

        if (args == null || args.length == 0) {
            out.add(CommandMessages.HEADER);
            out.add("Use '/hungerbridge help' for more details or '/hungerbridge token' for token subcommands");
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
                    out.add(ok ? "config reloaded" : "reload failed");
                    break;
                case "status":
                    out.add(admin.getStatus().toString());
                    break;
                case "audit": {
                    if (args.length >= 2 && args[1].equalsIgnoreCase("help")) {
                        out.add("audit [n]: show audit summary, optional n (default 20)");
                        break;
                    }
                    int n = 20;
                    if (args.length >= 2) try { n = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
                    List<String> lines = admin.getAuditSummary(n);
                    if (lines == null || lines.isEmpty()) {
                        out.add("no audit entries");
                    } else {
                        out.addAll(lines);
                    }
                    break;
                }
                case "token":
                case "tokens": {
                    if (args.length < 2) { out.add("token subcommands: list, create, revoke, rotate"); break; }
                    if (args.length >= 2 && args[1].equalsIgnoreCase("help")) {
                        out.add("token subcommands: list, create <id> [expiry], revoke <id>, rotate <id>");
                        out.add("Examples: '/hungerbridge token create admin', '/hungerbridge token create admin 3600'");
                        break;
                    }
                    switch (args[1].toLowerCase()) {
                        case "list":
                            out.add(admin.listTokens().keySet().toString());
                            break;
                        case "create": {
                            if (args.length < 3 || (args.length >= 3 && args[2].equalsIgnoreCase("help"))) {
                                out.add("usage: /hungerbridge token create <id> [expiry]");
                                out.add("example: /hungerbridge token create admin 3600");
                                break;
                            }

                            String id = args[2];
                            long expiry = 0L;
                            if (args.length >= 4) {
                                try { expiry = Long.parseLong(args[3]); } catch (NumberFormatException ignored) {}
                            }
                            TokenManager.Token t = admin.createToken(id, expiry, List.of(), List.of());
                            if (t == null) out.add("error: unknown token id or policy: " + id); else out.add(CommandMessages.createdToken(t.id, t.secret));
                            break;
                        }
                        case "revoke": {
                            if (args.length < 3) { out.add("usage: /hungerbridge token revoke <id>"); break; }
                            boolean r = admin.revokeToken(args[2]);
                            out.add(r ? "revoked" : "not found");
                            break;
                        }
                        case "rotate": {
                            if (args.length < 3) { out.add("usage: /hungerbridge token rotate <id>"); break; }
                            TokenManager.Token t = admin.rotateToken(args[2]);
                            if (t == null) out.add("rotate failed"); else out.add(CommandMessages.rotatedToken(t.id, t.secret));
                            break;
                        }
                        default: out.add("unknown token subcommand");
                    }
                    break;
                }
                case "ip":
                    if (args.length >= 2 && args[1].equalsIgnoreCase("help")) {
                        out.add("ip: show ip whitelist/blacklist status");
                        break;
                    }
                    out.add(admin.getIpStatus().toString());
                    break;
                case "config":
                    Map<String, Object> cs = admin.getConfigStatus();
                    for (Map.Entry<String, Object> e : cs.entrySet()) out.add(e.getKey() + ": " + String.valueOf(e.getValue()));
                    break;
                default:
                    out.add("unknown subcommand");
            }
        } catch (Exception e) {
            out.add("error: " + e.getMessage());
        }

        return out;
    }

    private CommonCommandHandler() {}
}
