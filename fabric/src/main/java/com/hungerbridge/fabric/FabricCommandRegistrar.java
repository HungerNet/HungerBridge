package com.hungerbridge.fabric;

import com.hungerbridge.common.BridgeServer;
import com.hungerbridge.common.CommonCommandHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public final class FabricCommandRegistrar {

    public static void register(CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher, BridgeServer bridgeServer, String name) {
        var cmd = net.minecraft.commands.Commands.literal(name);

        cmd.executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[0]));

        cmd.then(net.minecraft.commands.Commands.literal("reload").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"reload"})));

        // Do NOT register a global '/reload' command here — that would override
        // the vanilla command. Platform-specific server-level reload listeners
        // should be implemented in the platform bootstrap (HungerBridgeFabric),
        // not by registering a root command.

        // audit (with optional numeric arg)
        cmd.then(net.minecraft.commands.Commands.literal("audit").executes(ctx -> {
            return runHandler(bridgeServer, ctx.getSource(), new String[]{"audit"});
        }).then(
                net.minecraft.commands.Commands.argument("n", IntegerArgumentType.integer(1)).executes(ctx -> {
                    int n = IntegerArgumentType.getInteger(ctx, "n");
                    return runHandler(bridgeServer, ctx.getSource(), new String[]{"audit", String.valueOf(n)});
                })
        ));

        // token
        var token = net.minecraft.commands.Commands.literal("token");
        token.executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"token"}));
        token.then(net.minecraft.commands.Commands.literal("list").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "list"})));
        token.then(net.minecraft.commands.Commands.literal("help").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "help"})));

        var createLiteral = net.minecraft.commands.Commands.literal("create");
        var idArg = net.minecraft.commands.Commands.argument("id", StringArgumentType.word());
        var policyArg = net.minecraft.commands.Commands.argument("policy", StringArgumentType.word());
        // token create <id> <policy>
        createLiteral.then(idArg.then(policyArg.executes(ctx -> {
            String id = StringArgumentType.getString(ctx, "id");
            String policy = StringArgumentType.getString(ctx, "policy");
            return runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "create", id, policy});
        })));

        token.then(createLiteral);

        token.then(net.minecraft.commands.Commands.literal("revoke").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "revoke"})).then(
            net.minecraft.commands.Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                String id = StringArgumentType.getString(ctx, "id");
                return runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "revoke", id});
            })
        ));

        token.then(net.minecraft.commands.Commands.literal("remove").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "remove"})).then(
            net.minecraft.commands.Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                String id = StringArgumentType.getString(ctx, "id");
                return runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "remove", id});
            })
        ));

        cmd.then(token);

        // ip command
        cmd.then(net.minecraft.commands.Commands.literal("ip").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"ip"})));

        dispatcher.register(cmd);
    }

    private static int runHandler(BridgeServer bridgeServer, net.minecraft.commands.CommandSourceStack source, String[] args) {
        java.util.List<String> lines = CommonCommandHandler.handle(bridgeServer, source, args);
        for (String line : lines) {
            ChatFormatting style = styleFor(line);
            String prev = Thread.currentThread().getName();
            try {
                Thread.currentThread().setName("HungerBridge");
                source.sendSuccess(() -> Component.literal(line).withStyle(style), false);
            } finally {
                try { Thread.currentThread().setName(prev); } catch (Exception ignored) {}
            }
        }
        return 1;
    }

    private static ChatFormatting styleFor(String line) {
        if (line.startsWith("Error:")) return ChatFormatting.RED;
        if (line.startsWith("Warning:")) return ChatFormatting.GOLD;
        if (line.startsWith("Success:")) return ChatFormatting.GREEN;
        if (line.startsWith("Usage:") || line.startsWith("Subcommands:") || line.startsWith("Tokens Subcommands:")) return ChatFormatting.AQUA;
        return ChatFormatting.GRAY;
    }

    private FabricCommandRegistrar() {}
}
