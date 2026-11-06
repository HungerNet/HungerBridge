package com.hungerbridge.paper;

import com.hungerbridge.common.BridgeServer;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Binds the {@code hungerbridge} command declared in plugin.yml ({@code hb} is its alias).
 * Console-only enforcement lives in {@link HbCommand}.
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
