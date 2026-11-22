package com.hungerbridge.fabric;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Command source for {@code /server/run}: receives the command's feedback directly from
 * {@code CommandSourceStack} instead of the console. The console only sees the feedback
 * when {@code echo} is set (then exactly as vanilla prints the console's own feedback).
 * Admin broadcasts ({@code [Server: ...]} to operators) are not sent for bridge commands.
 */
final class CapturingCommandSource implements CommandSource {

    private final List<String> lines = new ArrayList<>();
    private final Consumer<Component> echo;

    CapturingCommandSource(Consumer<Component> echo) {
        this.echo = echo;
    }

    @Override
    public void sendSystemMessage(Component message) {
        String text = message.getString();
        synchronized (lines) {
            for (String line : text.split("\n", -1)) lines.add(line);
        }
        if (echo != null) echo.accept(message);
    }

    @Override
    public boolean acceptsSuccess() {
        return true;
    }

    @Override
    public boolean acceptsFailure() {
        return true;
    }

    @Override
    public boolean shouldInformAdmins() {
        return false;
    }

    List<String> lines() {
        synchronized (lines) {
            return List.copyOf(lines);
        }
    }
}
