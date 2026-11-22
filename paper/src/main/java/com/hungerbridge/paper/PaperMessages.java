package com.hungerbridge.paper;

import com.hungerbridge.common.message.BridgeMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Renders {@link BridgeMessage}s as Adventure components (same palette as Fabric's renderer). */
final class PaperMessages {

    private PaperMessages() {}

    static Component render(BridgeMessage message) {
        TextComponent.Builder line = Component.text();
        for (BridgeMessage.Segment segment : message.segments()) {
            Component part = Component.text(segment.text(), color(segment.tone()));
            line.append(segment.bold() ? part.decorate(TextDecoration.BOLD) : part);
        }
        return line.build();
    }

    static NamedTextColor color(BridgeMessage.Tone tone) {
        return switch (tone) {
            case BRAND -> NamedTextColor.DARK_AQUA;
            case TEXT -> NamedTextColor.WHITE;
            case MUTED -> NamedTextColor.GRAY;
            case VALUE -> NamedTextColor.AQUA;
            case COMMAND -> NamedTextColor.YELLOW;
            case SUCCESS -> NamedTextColor.GREEN;
            case WARNING -> NamedTextColor.GOLD;
            case ERROR -> NamedTextColor.RED;
        };
    }
}
