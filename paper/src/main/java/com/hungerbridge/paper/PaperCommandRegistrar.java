package com.hungerbridge.paper;

import com.hungerbridge.common.BridgeServer;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Register HungerBridge brigadier commands on Paper so they are hidden from players
 * and only executable from the console. Falls back to plugin command registration
 * when brigadier registration is not available.
 */
public final class PaperCommandRegistrar {

    public static void register(JavaPlugin plugin, BridgeServer bridgeServer) {
        // Register as plugin commands directly. This avoids any Fabric-specific
        // compile-time dependency while keeping the Paper entrypoints functional.
        HbCommand exec = new HbCommand(bridgeServer);
        var rootCmd = plugin.getCommand(com.hungerbridge.common.CommandConstants.ROOT);
        if (rootCmd != null) {
            rootCmd.setExecutor(exec);
        }
        var aliasCmd = plugin.getCommand(com.hungerbridge.common.CommandConstants.ALIAS);
        if (aliasCmd != null) {
            aliasCmd.setExecutor(exec);
        }
    }

    private PaperCommandRegistrar() {}
}
