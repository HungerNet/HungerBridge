package com.hungerbridge.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hungerbridge.common.security.TokenManager;
import com.hungerbridge.common.testing.SignedHttp;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-language protocol test: the same vectors file is verified by HungerBridgePy
 * (tests/data/protocol-vectors.json). Any divergence between the Java server and the
 * Python client canonicalization/signing breaks one of the two suites.
 */
public final class ProtocolVectorsTest {

    private static JsonObject vectors() throws Exception {
        try (InputStream in = ProtocolVectorsTest.class.getResourceAsStream("/protocol-vectors.json")) {
            assertNotNull(in, "protocol-vectors.json missing from test resources");
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    public void canonicalJsonMatchesPythonClient() throws Exception {
        for (JsonElement el : vectors().getAsJsonArray("canonical_json")) {
            JsonObject c = el.getAsJsonObject();
            String name = c.get("name").getAsString();
            String expected = c.get("canonical").getAsString();
            assertEquals(expected, TokenManager.canonicalizeBody(c.get("input").getAsString()), name + " (raw input)");
            // what the server actually receives from the client is the canonical text itself
            assertEquals(expected, TokenManager.canonicalizeBody(expected), name + " (fixed point)");
        }
    }

    @Test
    public void pathNormalizationMatchesPythonClient() throws Exception {
        for (JsonElement el : vectors().getAsJsonArray("paths")) {
            JsonObject c = el.getAsJsonObject();
            assertEquals(c.get("normalized").getAsString(), TokenManager.normalizePath(c.get("input").getAsString()), c.toString());
        }
    }

    @Test
    public void signaturesMatchPythonClient() throws Exception {
        JsonObject sig = vectors().getAsJsonObject("signatures");
        byte[] key = SignedHttp.hex(sig.get("secret_hex").getAsString());
        for (JsonElement el : sig.getAsJsonArray("cases")) {
            JsonObject c = el.getAsJsonObject();
            String sts = TokenManager.canonicalRequest(
                    c.get("method").getAsString(), c.get("path").getAsString(),
                    c.get("timestamp").getAsString(), c.get("nonce").getAsString(), c.get("body").getAsString());
            assertEquals(c.get("string_to_sign").getAsString(), sts);
            assertEquals(c.get("signature").getAsString(), TokenManager.hmacHex(key, sts));
        }
    }

    @Test
    public void nonBmpCharactersSurviveTheWire() {
        // Regression: the client used to send "\\u1f600" for U+1F600, which Java decodes as
        // U+1F60 followed by '0'. The surrogate-pair form must decode to the original text.
        String body = "{\"command\":\"say \\ud83d\\ude00\"}";
        assertEquals("say 😀", JsonParser.parseString(body).getAsJsonObject().get("command").getAsString());
        assertEquals(body, TokenManager.canonicalizeBody(body));
    }
}
