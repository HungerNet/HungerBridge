package com.hungerbridge.paper;

import com.hungerbridge.common.AdminService;
import com.hungerbridge.common.CommonCommandHandler;
import com.hungerbridge.common.CommandMessages;
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
            send(sender, CommandMessages.HEADER);
            send(sender, "Use '/hungerbridge help' for more details or '/hungerbridge tokens' for token subcommands");
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
