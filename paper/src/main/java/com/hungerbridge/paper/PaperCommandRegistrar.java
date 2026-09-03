package com.hungerbridge.paper;

import com.hungerbridge.common.BridgeServer;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperCommandRegistrar {

    public static void register(JavaPlugin plugin, BridgeServer bridgeServer) {
        HbCommand exec = new HbCommand(bridgeServer);
        if (plugin.getCommand("hungerbridge") != null) plugin.getCommand("hungerbridge").setExecutor(exec);
        if (plugin.getCommand("hb") != null) plugin.getCommand("hb").setExecutor(exec);
    }

    private PaperCommandRegistrar() {}
}
