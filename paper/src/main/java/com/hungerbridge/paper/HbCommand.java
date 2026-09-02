package com.hungerbridge.paper;

import com.hungerbridge.common.AdminService;
import com.hungerbridge.common.BridgeServer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;

public final class HbCommand implements CommandExecutor {

    private final BridgeServer bridgeServer;

    public HbCommand(BridgeServer bridgeServer) {
        this.bridgeServer = bridgeServer;
    }

    private void send(CommandSender sender, String msg) {
        sender.sendMessage(msg);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        AdminService admin = bridgeServer.getAdminService();
        if (admin == null) {
            send(sender, "Admin service not available");
            return true;
        }
        if (args.length == 0) {
            send(sender, "HungerBridge admin commands: reload status probe audit tokens ip config");
            send(sender, "Use '/hb help' for more details or '/hb tokens' for token subcommands");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
            send(sender, "Usage: /hb <subcommand> [args]");
            send(sender, "Subcommands: reload, status, probe, audit [n], tokens, ip, config");
            send(sender, "Tokens subcommands: list, create <ttl> <whitelist(comma)> <blacklist(comma)>, revoke <id>, rotate <id>");
            return true;
        }
        try {
            switch (args[0].toLowerCase()) {
                case "reload":
                    boolean ok = admin.reloadConfig();
                    send(sender, ok ? "config reloaded" : "reload failed");
                    break;
                case "status":
                    send(sender, admin.getStatus().toString());
                    break;
                case "probe":
                    send(sender, admin.runProbe().toString());
                    break;
                case "audit":
                    int n = 20;
                    if (args.length >= 2) try { n = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
                    List<String> lines = admin.getAuditSummary(n);
                    for (String l : lines) send(sender, l);
                    break;
                case "tokens": {
                    if (args.length < 2) { send(sender, "tokens subcommands: list create revoke rotate"); break; }
                    switch (args[1].toLowerCase()) {
                        case "list":
                            send(sender, admin.listTokens().keySet().toString());
                            break;
                        case "create": {
                            long ttl = 3600L;
                            try { if (args.length >= 3) ttl = Long.parseLong(args[2]); } catch (NumberFormatException ignored) {}
                            List<String> wl = List.of();
                            List<String> bl = List.of();
                            if (args.length >= 4) wl = java.util.Arrays.stream(args[3].split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                            if (args.length >= 5) bl = java.util.Arrays.stream(args[4].split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                            com.hungerbridge.common.security.TokenManager.Token t = admin.createToken(ttl, wl, bl);
                            if (t == null) send(sender, "create failed"); else send(sender, "created: " + t.id + ":" + t.secret);
                            break;
                        }
                        case "revoke": {
                            if (args.length < 3) { send(sender, "usage: /hb tokens revoke <id>"); break; }
                            boolean r = admin.revokeToken(args[2]);
                            send(sender, r ? "revoked" : "not found");
                            break;
                        }
                        case "rotate": {
                            if (args.length < 3) { send(sender, "usage: /hb tokens rotate <id>"); break; }
                            com.hungerbridge.common.security.TokenManager.Token t = admin.rotateToken(args[2]);
                            if (t == null) send(sender, "rotate failed"); else send(sender, "rotated: " + t.id + ":" + t.secret);
                            break;
                        }
                        default: send(sender, "unknown tokens subcommand");
                    }
                    break;
                }
                case "ip":
                    send(sender, admin.getIpStatus().toString());
                    break;
                case "config":
                    Map<String, Object> cs = admin.getConfigStatus();
                    for (Map.Entry<String, Object> e : cs.entrySet()) send(sender, e.getKey() + ": " + String.valueOf(e.getValue()));
                    break;
                default:
                    send(sender, "unknown subcommand");
            }
        } catch (Exception e) {
            send(sender, "error: " + e.getMessage());
        }
        return true;
    }
}
