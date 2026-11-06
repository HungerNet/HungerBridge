package com.hungerbridge.paper;

import com.hungerbridge.common.BridgeServer;
import com.hungerbridge.common.CommandMessages;
import com.hungerbridge.common.CommonCommandHandler;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;

import java.util.List;

/**
 * {@code /hungerbridge} ({@code /hb}). Console only: token pickups print a passkey that
 * must not end up in player chat (same rule as the Fabric command's source predicate).
 */
public final class HbCommand implements CommandExecutor {

    private final BridgeServer bridgeServer;

    public HbCommand(BridgeServer bridgeServer) {
        this.bridgeServer = bridgeServer;
    }

    @SuppressWarnings("deprecation") // ChatColor is still the simplest legacy-colour API across 1.21.11–26.3
    private static String style(String msg) {
        if (msg.startsWith("Error:")) return ChatColor.RED + msg;
        if (msg.startsWith("Warning:")) return ChatColor.GOLD + msg;
        if (msg.startsWith("Success:")) return ChatColor.GREEN + msg;
        if (msg.startsWith("Usage:")) return ChatColor.AQUA + msg;
        return ChatColor.GRAY + msg;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            sender.sendMessage(style("Error: /" + label + " can only be used from the server console."));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(style(CommandMessages.HEADER));
            return true;
        }
        List<String> lines = (args.length == 1 && args[0].equalsIgnoreCase("help"))
                ? CommandMessages.helpLines()
                : CommonCommandHandler.handle(bridgeServer, sender, args);
        for (String l : lines) sender.sendMessage(style(l));
        return true;
    }
}
