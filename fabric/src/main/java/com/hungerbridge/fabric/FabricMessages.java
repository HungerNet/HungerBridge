package com.hungerbridge.fabric;

import com.hungerbridge.common.message.BridgeMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Renders {@link BridgeMessage}s as Minecraft components (same palette as Paper's renderer). */
final class FabricMessages {

    private FabricMessages() {}

    static Component render(BridgeMessage message) {
        MutableComponent line = Component.empty();
        for (BridgeMessage.Segment segment : message.segments()) {
            MutableComponent part = Component.literal(segment.text()).withStyle(color(segment.tone()));
            if (segment.bold()) part = part.withStyle(ChatFormatting.BOLD);
            line.append(part);
        }
        return line;
    }

    static ChatFormatting color(BridgeMessage.Tone tone) {
        return switch (tone) {
            case BRAND -> ChatFormatting.DARK_AQUA;
            case TEXT -> ChatFormatting.WHITE;
            case MUTED -> ChatFormatting.GRAY;
            case VALUE -> ChatFormatting.AQUA;
            case COMMAND -> ChatFormatting.YELLOW;
            case SUCCESS -> ChatFormatting.GREEN;
            case WARNING -> ChatFormatting.GOLD;
            case ERROR -> ChatFormatting.RED;
        };
    }
}
