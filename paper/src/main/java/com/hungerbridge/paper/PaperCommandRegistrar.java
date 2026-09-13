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
        // Try to register via Brigadier dispatcher so the command node is not visible
        // to players and only the console can execute it.
        try {
            Object craftServer = plugin.getServer();
            java.lang.reflect.Method getHandle = craftServer.getClass().getMethod("getHandle");
            Object nmsServer = getHandle.invoke(craftServer); // net.minecraft.server.MinecraftServer
            // server.getCommands().getDispatcher()
            java.lang.reflect.Method getCommands = nmsServer.getClass().getMethod("getCommands");
            Object cmdManager = getCommands.invoke(nmsServer);
            java.lang.reflect.Method getDispatcher = cmdManager.getClass().getMethod("getDispatcher");
            @SuppressWarnings("unchecked")
            com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher =
                    (com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>) getDispatcher.invoke(cmdManager);

            var cmd = net.minecraft.commands.Commands.literal(com.hungerbridge.common.CommandConstants.ROOT)
                    .requires(src -> src.getEntity() == null);

            cmd.executes(ctx -> {
                return com.hungerbridge.fabric.FabricCommandRegistrar.runHandler(bridgeServer, ctx.getSource(), new String[0]);
            });

            // Mirror subcommands by delegating to the Fabric registrar style implementation
            com.hungerbridge.fabric.FabricCommandRegistrar.register(dispatcher, bridgeServer, com.hungerbridge.common.CommandConstants.ROOT);
            com.hungerbridge.fabric.FabricCommandRegistrar.register(dispatcher, bridgeServer, com.hungerbridge.common.CommandConstants.ALIAS);
            return;
        } catch (Throwable ignored) {
            // Fall back to legacy plugin command registration if brigadier not available
        }

        // Fallback: register as plugin commands but do not set permissions here.
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
