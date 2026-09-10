package com.hungerbridge.paper;

import com.hungerbridge.common.BridgeServer;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperCommandRegistrar {

    public static void register(JavaPlugin plugin, BridgeServer bridgeServer) {
        HbCommand exec = new HbCommand(bridgeServer);
        var rootCmd = plugin.getCommand(com.hungerbridge.common.CommandConstants.ROOT);
        if (rootCmd != null) {
            rootCmd.setExecutor(exec);
            try { rootCmd.setPermission("hungerbridge.admin"); } catch (Exception ignored) {}
            try { rootCmd.setPermissionMessage("You do not have permission to use this command"); } catch (Exception ignored) {}
        }
        var aliasCmd = plugin.getCommand(com.hungerbridge.common.CommandConstants.ALIAS);
        if (aliasCmd != null) {
            aliasCmd.setExecutor(exec);
            try { aliasCmd.setPermission("hungerbridge.admin"); } catch (Exception ignored) {}
            try { aliasCmd.setPermissionMessage("You do not have permission to use this command"); } catch (Exception ignored) {}
        }
    }

    private PaperCommandRegistrar() {}
}
