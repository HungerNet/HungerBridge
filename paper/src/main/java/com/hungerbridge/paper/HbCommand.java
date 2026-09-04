package com.hungerbridge.paper;

import com.hungerbridge.common.AdminService;
import com.hungerbridge.common.CommonCommandHandler;
import com.hungerbridge.common.CommandMessages;
import com.hungerbridge.common.BridgeServer;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class HbCommand implements CommandExecutor {

    private final BridgeServer bridgeServer;

    public HbCommand(BridgeServer bridgeServer) {
        this.bridgeServer = bridgeServer;
    }

    private void send(CommandSender sender, String msg) {
        sender.sendMessage(style(msg));
    }

    private String style(String msg) {
        if (msg.startsWith("Error:")) return ChatColor.RED + msg;
        if (msg.startsWith("Warning:")) return ChatColor.GOLD + msg;
        if (msg.startsWith("Success:")) return ChatColor.GREEN + msg;
        if (msg.startsWith("Usage:") || msg.startsWith("Subcommands:") || msg.startsWith("Tokens Subcommands:") || msg.startsWith("Token subcommands:")) return ChatColor.AQUA + msg;
        return ChatColor.GRAY + msg;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        AdminService admin = bridgeServer.getAdminService();
        if (admin == null) {
            send(sender, CommandMessages.error("Admin service is not available."));
            return true;
        }
        if (args.length == 0) {
            send(sender, CommandMessages.HEADER);
            send(sender, "Use '/hungerbridge help' for more details or '/hungerbridge token' for the token subcommands.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
            for (String l : CommandMessages.helpLines()) send(sender, l);
            return true;
        }
        List<String> lines = CommonCommandHandler.handle(bridgeServer, args);
        for (String l : lines) send(sender, l);
        return true;
    }
}
