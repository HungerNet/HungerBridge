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

        cmd.then(net.minecraft.commands.Commands.literal("status").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"status"})));
        cmd.then(net.minecraft.commands.Commands.literal("reload").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"reload"})));

        // audit (with optional numeric arg)
        cmd.then(net.minecraft.commands.Commands.literal("audit").executes(ctx -> {
            return runHandler(bridgeServer, ctx.getSource(), new String[]{"audit"});
        }).then(
                net.minecraft.commands.Commands.argument("n", IntegerArgumentType.integer(1)).executes(ctx -> {
                    int n = IntegerArgumentType.getInteger(ctx, "n");
                    return runHandler(bridgeServer, ctx.getSource(), new String[]{"audit", String.valueOf(n)});
                })
        ));

        cmd.then(net.minecraft.commands.Commands.literal("config").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"config"})));

        // help
        cmd.then(net.minecraft.commands.Commands.literal("help").executes(ctx -> {
            return runHandler(bridgeServer, ctx.getSource(), new String[]{"help"});
        }));

        // token
        var token = net.minecraft.commands.Commands.literal("token");
        token.executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"token"}));
        token.then(net.minecraft.commands.Commands.literal("list").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "list"})));
        token.then(net.minecraft.commands.Commands.literal("help").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "help"})));

        var createLiteral = net.minecraft.commands.Commands.literal("create");
        createLiteral.executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "create"}));
        var idArg = net.minecraft.commands.Commands.argument("id", StringArgumentType.word());
        // support: id
        createLiteral.then(idArg.executes(ctx -> {
            String id = StringArgumentType.getString(ctx, "id");
            return runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "create", id});
        }));
        // support: id name
        var nameArg = net.minecraft.commands.Commands.argument("name", StringArgumentType.word());
        createLiteral.then(idArg.then(nameArg.executes(ctx -> {
            String id = StringArgumentType.getString(ctx, "id");
            String nameVal1 = StringArgumentType.getString(ctx, "name");
            return runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "create", id, nameVal1});
        })));
        // support: id expiry
        var expiryArg = net.minecraft.commands.Commands.argument("expiry", IntegerArgumentType.integer(0));
        createLiteral.then(idArg.then(expiryArg.executes(ctx -> {
            String id = StringArgumentType.getString(ctx, "id");
            int expiry = IntegerArgumentType.getInteger(ctx, "expiry");
            return runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "create", id, String.valueOf(expiry)});
        })));
        // support: id name expiry
        createLiteral.then(idArg.then(nameArg.then(expiryArg.executes(ctx -> {
            String id = StringArgumentType.getString(ctx, "id");
            String nameVal2 = StringArgumentType.getString(ctx, "name");
            int expiry = IntegerArgumentType.getInteger(ctx, "expiry");
            return runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "create", id, nameVal2, String.valueOf(expiry)});
        }))));

        token.then(createLiteral);

        token.then(net.minecraft.commands.Commands.literal("revoke").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "revoke"})).then(
            net.minecraft.commands.Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                String id = StringArgumentType.getString(ctx, "id");
                return runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "revoke", id});
            })
        ));

        token.then(net.minecraft.commands.Commands.literal("rotate").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "rotate"})).then(
            net.minecraft.commands.Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                String id = StringArgumentType.getString(ctx, "id");
                return runHandler(bridgeServer, ctx.getSource(), new String[]{"token", "rotate", id});
            })
        ));

        cmd.then(token);

        // ip command
        cmd.then(net.minecraft.commands.Commands.literal("ip").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"ip"})));

        dispatcher.register(cmd);
    }

    private static int runHandler(BridgeServer bridgeServer, net.minecraft.commands.CommandSourceStack source, String[] args) {
        java.util.List<String> lines = CommonCommandHandler.handle(bridgeServer, args);
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
