package com.hungerbridge.common.log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a chat/console message into its plain text for {@code POST /server/log} with
 * {@code "normalize": true}. Runs on the server so every client and loader gets the same
 * result. In order:
 *
 * <ol>
 *   <li><b>Structured:</b> a JSON text component ({@code {"text":...}}, {@code [...]} or a
 *       JSON string) is flattened: {@code text}, {@code translate} (its {@code fallback} when
 *       present), {@code keybind}, {@code selector}, then {@code extra}.</li>
 *   <li>ANSI escapes and legacy {@code §} codes are removed.</li>
 *   <li>Leading chat-channel decorations listed in {@link #RULES} are removed (repeatedly).
 *       Each rule names the Minecraft versions it applies to.</li>
 * </ol>
 *
 * A message that would become empty is returned unchanged.
 */
public final class MessageNormalizer {

    /** A leading decoration, applied for Minecraft versions in {@code [since, until)} ({@code null} = open). */
    public record Rule(String name, Pattern pattern, String since, String until) {
        public boolean appliesTo(String minecraftVersion) {
            if (since == null && until == null) return true;
            List<Integer> v = parseVersion(minecraftVersion);
            if (v == null) return false;
            return (since == null || compare(v, parseVersion(since)) >= 0)
                    && (until == null || compare(v, parseVersion(until)) < 0);
        }
    }

    /** Decorations removed by normalization; all apply to every supported version (1.21.11–26.3). */
    public static final List<Rule> RULES = List.of(
            rule("system chat prefix", "System chat:\\s*"),
            rule("[System] tag", "\\[System]\\s*"),
            rule("[CHAT] tag", "\\[CHAT]\\s*"),
            rule("unsigned chat tag", "\\[Not Secure]\\s*"),
            rule("/say prefix", "\\[Server]\\s*"),
            rule("RCON prefix", "\\[Rcon]\\s*"));

    private MessageNormalizer() {}

    private static Rule rule(String name, String regex) {
        return new Rule(name, Pattern.compile("^" + regex, Pattern.CASE_INSENSITIVE), null, null);
    }

    public static String normalize(String message, String minecraftVersion) {
        if (message == null) return "";
        String text = flattenComponent(message.strip());
        text = LineFormat.plain(text).strip();
        boolean changed = true;
        while (changed && !text.isEmpty()) {
            changed = false;
            for (Rule r : RULES) {
                if (!r.appliesTo(minecraftVersion)) continue;
                Matcher m = r.pattern().matcher(text);
                if (m.find() && m.end() < text.length()) {
                    text = text.substring(m.end()).strip();
                    changed = true;
                }
            }
        }
        return text.isEmpty() ? message : text;
    }

    /** The plain text of a JSON text component, or {@code text} itself if it is not one. */
    static String flattenComponent(String text) {
        if (text.isEmpty()) return text;
        char first = text.charAt(0);
        if (first != '{' && first != '[' && first != '"') return text;
        try {
            JsonElement root = JsonParser.parseString(text);
            if (root.isJsonObject() && !looksLikeComponent(root.getAsJsonObject())) return text;
            StringBuilder out = new StringBuilder();
            append(root, out);
            return out.toString();
        } catch (RuntimeException notJson) {
            return text;
        }
    }

    private static boolean looksLikeComponent(JsonObject o) {
        return o.has("text") || o.has("translate") || o.has("extra") || o.has("keybind") || o.has("selector");
    }

    private static void append(JsonElement el, StringBuilder out) {
        if (el == null || el.isJsonNull()) return;
        if (el.isJsonPrimitive()) {
            out.append(el.getAsString());
            return;
        }
        if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) append(child, out);
            return;
        }
        JsonObject o = el.getAsJsonObject();
        if (o.has("text")) out.append(o.get("text").getAsString());
        else if (o.has("translate")) out.append(o.has("fallback") ? o.get("fallback").getAsString() : o.get("translate").getAsString());
        else if (o.has("keybind")) out.append(o.get("keybind").getAsString());
        else if (o.has("selector")) out.append(o.get("selector").getAsString());
        if (o.has("extra") && o.get("extra").isJsonArray()) {
            JsonArray extra = o.getAsJsonArray("extra");
            for (JsonElement child : extra) append(child, out);
        }
    }

    static List<Integer> parseVersion(String version) {
        if (version == null || !version.matches("\\d+(\\.\\d+)*")) return null;
        List<Integer> parts = new ArrayList<>();
        for (String p : version.split("\\.")) parts.add(Integer.parseInt(p));
        return parts;
    }

    static int compare(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }
}
