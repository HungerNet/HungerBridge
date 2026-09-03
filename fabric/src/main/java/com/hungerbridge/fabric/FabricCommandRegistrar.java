package com.hungerbridge.fabric;

import com.hungerbridge.common.BridgeServer;
import com.hungerbridge.common.CommonCommandHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

public final class FabricCommandRegistrar {

    public static void register(CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher, BridgeServer bridgeServer, String name) {
        var cmd = net.minecraft.commands.Commands.literal(name);

        cmd.executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[0]));

        cmd.then(net.minecraft.commands.Commands.literal("status").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"status"})));
        cmd.then(net.minecraft.commands.Commands.literal("probe").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"probe"})));
        cmd.then(net.minecraft.commands.Commands.literal("reload").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"reload"})));

        cmd.then(net.minecraft.commands.Commands.literal("audit").then(
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

        // tokens
        var tokens = net.minecraft.commands.Commands.literal("tokens");
        tokens.then(net.minecraft.commands.Commands.literal("list").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"tokens", "list"})));
        tokens.then(net.minecraft.commands.Commands.literal("help").executes(ctx -> runHandler(bridgeServer, ctx.getSource(), new String[]{"tokens", "help"})));

        var createLiteral = net.minecraft.commands.Commands.literal("create");
        var ttlArg = net.minecraft.commands.Commands.argument("ttl", IntegerArgumentType.integer(0));
        createLiteral.then(ttlArg.executes(ctx -> {
            int ttl = IntegerArgumentType.getInteger(ctx, "ttl");
            return runHandler(bridgeServer, ctx.getSource(), new String[]{"tokens", "create", String.valueOf(ttl)});
        }));

        var wlArg = net.minecraft.commands.Commands.argument("whitelist", StringArgumentType.word());
        createLiteral.then(ttlArg.then(wlArg.executes(ctx -> {
            int ttl = IntegerArgumentType.getInteger(ctx, "ttl");
            String wl = StringArgumentType.getString(ctx, "whitelist");
            return runHandler(bridgeServer, ctx.getSource(), new String[]{"tokens", "create", String.valueOf(ttl), wl});
        })));

        var blArg = net.minecraft.commands.Commands.argument("blacklist", StringArgumentType.word());
        createLiteral.then(ttlArg.then(wlArg.then(blArg.executes(ctx -> {
            int ttl = IntegerArgumentType.getInteger(ctx, "ttl");
            String wl = StringArgumentType.getString(ctx, "whitelist");
            String bl = StringArgumentType.getString(ctx, "blacklist");
            return runHandler(bridgeServer, ctx.getSource(), new String[]{"tokens", "create", String.valueOf(ttl), wl, bl});
        }))));

        tokens.then(createLiteral);

        tokens.then(net.minecraft.commands.Commands.literal("revoke").then(
                net.minecraft.commands.Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                    String id = StringArgumentType.getString(ctx, "id");
                    return runHandler(bridgeServer, ctx.getSource(), new String[]{"tokens", "revoke", id});
                })
        ));

        tokens.then(net.minecraft.commands.Commands.literal("rotate").then(
                net.minecraft.commands.Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                    String id = StringArgumentType.getString(ctx, "id");
                    return runHandler(bridgeServer, ctx.getSource(), new String[]{"tokens", "rotate", id});
                })
        ));

        cmd.then(tokens);

        dispatcher.register(cmd);
    }

    private static int runHandler(BridgeServer bridgeServer, net.minecraft.commands.CommandSourceStack source, String[] args) {
        java.util.List<String> lines = CommonCommandHandler.handle(bridgeServer, args);
        for (String l : lines) source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(l), false);
        return 1;
    }

    private FabricCommandRegistrar() {}
}
