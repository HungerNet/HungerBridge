package com.hungerbridge.paper;

import com.hungerbridge.common.BridgeServer;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperCommandRegistrar {

    public static void register(JavaPlugin plugin, BridgeServer bridgeServer) {
        HbCommand exec = new HbCommand(bridgeServer);
        if (plugin.getCommand(com.hungerbridge.common.CommandConstants.ROOT) != null)
            plugin.getCommand(com.hungerbridge.common.CommandConstants.ROOT).setExecutor(exec);
        if (plugin.getCommand(com.hungerbridge.common.CommandConstants.ALIAS) != null)
            plugin.getCommand(com.hungerbridge.common.CommandConstants.ALIAS).setExecutor(exec);
    }

    private PaperCommandRegistrar() {}
}
