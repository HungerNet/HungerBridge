package com.hungerbridge.common.message;

import java.util.List;

/**
 * One line of HungerBridge command output, rendered the same way on every loader.
 *
 * <pre>
 * HungerBridge » Token created                        HEADER   brand + title
 *   ✔ my-bot uses policy admin                        SUCCESS
 *     Configuration reloaded from disk                INFO
 *   • Pickup URL  /pickup/&lt;id&gt;?passkey=&lt;hex&gt;         DETAIL   key + value
 *   ▲ The pickup URL works once                       WARNING
 *   ✖ Unknown policy: admn                            ERROR
 *   › /hb token create &lt;id&gt; &lt;policy&gt;               USAGE
 * </pre>
 *
 * The glyphs and indentation carry the meaning, so the output is readable where colours are
 * not shown (Fabric's dedicated-server console prints plain text). Platforms map each
 * {@link Tone} to their colour API.
 */
public record BridgeMessage(Kind kind, String text, String value) {

    public enum Kind { HEADER, SUCCESS, INFO, DETAIL, WARNING, ERROR, USAGE }

    /** Semantic colour of a segment; Paper and Fabric map these to the same colours. */
    public enum Tone { BRAND, TEXT, MUTED, VALUE, COMMAND, SUCCESS, WARNING, ERROR }

    /** A piece of a rendered line. */
    public record Segment(String text, Tone tone, boolean bold) {}

    public static final String BRAND = "HungerBridge";

    public static BridgeMessage header(String title) { return new BridgeMessage(Kind.HEADER, title, null); }
    public static BridgeMessage success(String text) { return new BridgeMessage(Kind.SUCCESS, text, null); }
    public static BridgeMessage info(String text) { return new BridgeMessage(Kind.INFO, text, null); }
    public static BridgeMessage detail(String key, String value) { return new BridgeMessage(Kind.DETAIL, key, value); }
    public static BridgeMessage warning(String text) { return new BridgeMessage(Kind.WARNING, text, null); }
    public static BridgeMessage error(String text) { return new BridgeMessage(Kind.ERROR, text, null); }
    public static BridgeMessage usage(String command) { return new BridgeMessage(Kind.USAGE, command, null); }

    public List<Segment> segments() {
        return switch (kind) {
            case HEADER -> List.of(new Segment(BRAND, Tone.BRAND, true), new Segment(" » ", Tone.MUTED, false), new Segment(text, Tone.TEXT, false));
            case SUCCESS -> List.of(new Segment("  ✔ ", Tone.SUCCESS, false), new Segment(text, Tone.TEXT, false));
            case INFO -> List.of(new Segment("    ", Tone.MUTED, false), new Segment(text, Tone.MUTED, false));
            case DETAIL -> List.of(new Segment("  • ", Tone.MUTED, false), new Segment(text, Tone.MUTED, false),
                    new Segment("  ", Tone.MUTED, false), new Segment(value == null ? "" : value, Tone.VALUE, false));
            case WARNING -> List.of(new Segment("  ▲ ", Tone.WARNING, false), new Segment(text, Tone.WARNING, false));
            case ERROR -> List.of(new Segment("  ✖ ", Tone.ERROR, false), new Segment(text, Tone.ERROR, false));
            case USAGE -> List.of(new Segment("  › ", Tone.MUTED, false), new Segment(text, Tone.COMMAND, false));
        };
    }

    /** The line without colours. */
    public String plain() {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments()) sb.append(s.text());
        return sb.toString();
    }

    @Override
    public String toString() {
        return plain();
    }
}
