package com.hungerbridge.common;

import com.hungerbridge.common.log.BridgeLogger;
import com.hungerbridge.common.log.LineFormat;
import com.hungerbridge.common.log.MessageNormalizer;
import com.hungerbridge.common.message.BridgeMessage;
import com.hungerbridge.common.security.TokenManager;
import com.hungerbridge.common.testing.FakeExecutor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Canonical line format, message normalization and the command message model. */
public final class LogFormatTest {

    private static final String TIME = "\\[\\d{2}:\\d{2}:\\d{2}] ";

    @Test
    public void customLoggerLineMatchesTheRequiredFormat() {
        String line = LineFormat.format(0L, "CUSTOMLEVEL", "CustomThread", "CustomLogger", "example message", null);
        assertTrue(line.matches(TIME + "\\[CustomThread/CUSTOMLEVEL]: \\[CustomLogger] example message"), line);
    }

    @Test
    public void minecraftLoggersHaveNoPrefixAndMessagesAreNotPrefixedTwice() {
        assertTrue(LineFormat.format(0L, "INFO", "Server thread", "net.minecraft.server.MinecraftServer", "Done (3.2s)!", null)
                .matches(TIME + "\\[Server thread/INFO]: Done \\(3\\.2s\\)!"));
        assertTrue(LineFormat.format(0L, "INFO", "Server thread", "LuckPerms", "[LuckPerms] Loaded", null)
                .endsWith("]: [LuckPerms] Loaded"));
        assertTrue(LineFormat.format(0L, "INFO", "t", null, "x", null).endsWith("[t/INFO]: x"));
    }

    @Test
    public void linesArePlainAndKeepStackTraces() {
        String line = LineFormat.format(0L, "ERROR", "t", "Mod", "\u001B[31mred\u001B[0m \u00A7agreen", "java.lang.X: y\n\tat A.b(A.java:1)");
        assertTrue(line.endsWith("[Mod] red green\njava.lang.X: y\n\tat A.b(A.java:1)"), line);
        // a literal backslash-n stays two characters (protocol v1 turned it into a newline)
        assertTrue(LineFormat.format(0L, "INFO", "t", "x", "C:\\new", null).endsWith("C:\\new"));
    }

    @Test
    public void normalizationStripsDecorationsFormattingAndComponents() {
        assertEquals("Hello", MessageNormalizer.normalize("System chat: Hello", "26.3"));
        assertEquals("Hello", MessageNormalizer.normalize("[Not Secure] [Server] Hello", "1.21.11"));
        assertEquals("Hello", MessageNormalizer.normalize("\u00A7e[System] [CHAT] \u00A7lHello", "26.1.2"));
        assertEquals("Hello world", MessageNormalizer.normalize("{\"text\":\"Hello \",\"extra\":[{\"text\":\"world\",\"color\":\"gold\"}]}", "26.2"));
        assertEquals("Joined", MessageNormalizer.normalize("[{\"translate\":\"x.y\",\"fallback\":\"Joined\"}]", "26.3"));
        assertEquals("plain text", MessageNormalizer.normalize("  plain text  ", "26.3"));
        // nothing left after stripping: the original is kept
        assertEquals("System chat:", MessageNormalizer.normalize("System chat:", "26.3"));
        // not a component: left alone
        assertEquals("{not json", MessageNormalizer.normalize("{not json", "26.3"));
        assertEquals("{\"a\":1}", MessageNormalizer.normalize("{\"a\":1}", "26.3"));
    }

    @Test
    public void normalizationRulesAreDeclaredForEveryVersion() {
        for (MessageNormalizer.Rule rule : MessageNormalizer.RULES) {
            for (String version : List.of("1.21.11", "26.1.2", "26.2", "26.3", "standalone")) {
                assertTrue(rule.appliesTo(version), rule.name() + " / " + version);
            }
        }
    }

    @Test
    public void levelNamesAreValidatedAndBounded() {
        assertTrue(BridgeLogger.LEVEL_NAME.matcher("CUSTOM_LEVEL2").matches());
        assertFalse(BridgeLogger.LEVEL_NAME.matcher("bad level").matches());
        assertFalse(BridgeLogger.LEVEL_NAME.matcher("2X").matches());
        assertEquals("WARN", BridgeLogger.level("warn").name());
        assertEquals("MYLEVEL", BridgeLogger.level("myLevel").name());
    }

    @Test
    public void messagesReadWellWithoutColour() {
        assertEquals("HungerBridge » Token created", BridgeMessage.header("Token created").plain());
        assertEquals("  ✔ done", BridgeMessage.success("done").plain());
        assertEquals("  • Pickup URL  /pickup/x", BridgeMessage.detail("Pickup URL", "/pickup/x").plain());
        assertEquals("  ▲ careful", BridgeMessage.warning("careful").plain());
        assertEquals("  ✖ broken", BridgeMessage.error("broken").plain());
        assertEquals("  › /hb help", BridgeMessage.usage("/hb help").plain());
    }

    @Test
    public void helpIsTheSameWithAndWithoutArguments() {
        assertEquals(CommonCommandHandler.handle(null, new String[0]), CommonCommandHandler.handle(null, new String[] {"help"}));
        assertEquals(BridgeMessage.header("Commands"), CommonCommandHandler.handle(null, new String[0]).get(0));
        // "tokens" was a Paper-only alias
        assertEquals(BridgeMessage.error("Unknown command: tokens"), CommonCommandHandler.handle(null, new String[] {"tokens"}).get(1));
    }

    @Test
    public void tokenCreateOutputKeepsThePickupUrlOnOneLine() throws Exception {
        // CalagoAPI's createBridgeToken() finds "/pickup/<id>?passkey=<hex>" in the console.
        Path dir = Files.createTempDirectory("hb-create");
        Files.writeString(dir.resolve("policies.yaml"), "policies:\n  - id: admin\n    permissions: [\"*\"]\n");
        Config cfg = Config.load(dir, entry -> {});
        cfg.setTokenManager(new TokenManager(dir, null));
        BridgeServer server = new BridgeServer(dir, cfg, entry -> {}, new FakeExecutor());

        List<BridgeMessage> out = CommonCommandHandler.handle(server, new String[] {"token", "create", "bot", "admin", "60"});
        assertEquals(BridgeMessage.header("Token created"), out.get(0));
        assertEquals(BridgeMessage.success("bot uses policy admin"), out.get(1));
        assertTrue(out.stream().anyMatch(m -> m.plain().matches("  • Pickup URL  /pickup/[0-9a-f-]+\\?passkey=[0-9a-f]+")), out.toString());
        assertTrue(out.stream().anyMatch(m -> m.kind() == BridgeMessage.Kind.DETAIL && m.text().equals("Expires")));

        assertEquals(BridgeMessage.error("Unknown policy: nope"),
                CommonCommandHandler.handle(server, new String[] {"token", "create", "x", "nope"}).get(1));
        assertEquals(BridgeMessage.error("A token with id bot already exists or is waiting for pickup"),
                CommonCommandHandler.handle(server, new String[] {"token", "create", "bot", "admin"}).get(1));
        List<BridgeMessage> listed = CommonCommandHandler.handle(server, new String[] {"token", "list"});
        assertEquals(BridgeMessage.header("Tokens (1)"), listed.get(0));
        assertTrue(listed.get(1).value().startsWith("policy admin · active · expires 20"), listed.toString());
    }
}
