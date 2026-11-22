package com.hungerbridge.paper;

import com.hungerbridge.common.BridgeServer;
import com.hungerbridge.common.CommonCommandHandler;
import com.hungerbridge.common.message.BridgeMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;

/**
 * {@code /hungerbridge} ({@code /hb}). Console only: token pickups print a secret that must
 * not end up in player chat (same rule as the Fabric command). All behaviour lives in
 * {@link CommonCommandHandler}.
 */
public final class HbCommand implements CommandExecutor {

    private final BridgeServer bridgeServer;

    public HbCommand(BridgeServer bridgeServer) {
        this.bridgeServer = bridgeServer;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            sender.sendMessage(PaperMessages.render(BridgeMessage.error("/" + label + " can only be used from the server console")));
            return true;
        }
        for (BridgeMessage message : CommonCommandHandler.handle(bridgeServer, args)) {
            sender.sendMessage(PaperMessages.render(message));
        }
        return true;
    }
}
