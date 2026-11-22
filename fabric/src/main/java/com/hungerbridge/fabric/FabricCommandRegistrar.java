package com.hungerbridge.fabric;

import com.hungerbridge.common.BridgeServer;
import com.hungerbridge.common.CommonCommandHandler;
import com.hungerbridge.common.message.BridgeMessage;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Brigadier tree for {@code /hungerbridge} and {@code /hb}. Console only (sources without
 * an entity), mirroring the Paper command; all logic lives in {@link CommonCommandHandler}.
 */
public final class FabricCommandRegistrar {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, BridgeServer bridgeServer, String name) {
        var cmd = Commands.literal(name).requires(src -> src.getEntity() == null);
        cmd.executes(ctx -> run(bridgeServer, ctx.getSource()));
        cmd.then(Commands.literal("help").executes(ctx -> run(bridgeServer, ctx.getSource(), "help")));
        cmd.then(Commands.literal("reload").executes(ctx -> run(bridgeServer, ctx.getSource(), "reload")));

        var token = Commands.literal("token").executes(ctx -> run(bridgeServer, ctx.getSource(), "token"));
        token.then(Commands.literal("list").executes(ctx -> run(bridgeServer, ctx.getSource(), "token", "list")));

        // token create <id> <policy> [expirySeconds]
        token.then(Commands.literal("create")
                .executes(ctx -> run(bridgeServer, ctx.getSource(), "token", "create"))
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("policy", StringArgumentType.word())
                                .executes(ctx -> run(bridgeServer, ctx.getSource(), "token", "create",
                                        StringArgumentType.getString(ctx, "id"), StringArgumentType.getString(ctx, "policy")))
                                .then(Commands.argument("expiry", LongArgumentType.longArg(0))
                                        .executes(ctx -> run(bridgeServer, ctx.getSource(), "token", "create",
                                                StringArgumentType.getString(ctx, "id"), StringArgumentType.getString(ctx, "policy"),
                                                Long.toString(LongArgumentType.getLong(ctx, "expiry"))))))));

        for (String sub : new String[] {"rotate", "revoke", "remove"}) {
            token.then(Commands.literal(sub)
                    .executes(ctx -> run(bridgeServer, ctx.getSource(), "token", sub))
                    .then(Commands.argument("id", StringArgumentType.word())
                            .executes(ctx -> run(bridgeServer, ctx.getSource(), "token", sub, StringArgumentType.getString(ctx, "id")))));
        }
        cmd.then(token);
        dispatcher.register(cmd);
    }

    private static int run(BridgeServer bridgeServer, CommandSourceStack source, String... args) {
        for (BridgeMessage message : CommonCommandHandler.handle(bridgeServer, args)) {
            source.sendSuccess(() -> FabricMessages.render(message), false);
        }
        return 1;
    }
    private FabricCommandRegistrar() {}
}
