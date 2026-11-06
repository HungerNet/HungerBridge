package com.hungerbridge.common;

import com.google.gson.JsonObject;
import com.hungerbridge.common.security.TokenManager;
import com.hungerbridge.common.testing.FakeExecutor;
import com.hungerbridge.common.testing.SignedHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP round trips against an in-process BridgeServer (no Minecraft). */
public final class BridgeServerIntegrationTest {

    private Path dir;
    private BridgeServer server;
    private FakeExecutor executor;
    private TokenManager tm;
    private String base;
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final List<String> logged = new java.util.concurrent.CopyOnWriteArrayList<>();

    private void start(String securityYaml, String policiesYaml) throws Exception {
        dir = Files.createTempDirectory("hb-it");
        Files.writeString(dir.resolve("config.yaml"), "port: 0\nbind-address: 127.0.0.1\nplayers:\n  max-list: 2\n");
        // Generous limits unless a test is about rate limiting (the buckets are per IP/token).
        Files.writeString(dir.resolve("security.yaml"), securityYaml != null ? securityYaml
                : "rate-limits:\n  per-ip:\n    burst: 1000\n    rps: 1000\n  per-token:\n    burst: 1000\n    rps: 1000\n");
        if (policiesYaml != null) Files.writeString(dir.resolve("policies.yaml"), policiesYaml);
        Logger logger = (level, thread, message) -> logged.add(level + "|" + thread + "|" + message);
        Config config = Config.load(dir, logger);
        tm = new TokenManager(dir, logger);
        config.setTokenManager(tm);
        config.setPlatform("test");
        config.setMinecraftVersion("26.3");
        config.setBridgeVersion("test-version");
        executor = new FakeExecutor();
        server = new BridgeServer(dir, config, logger, executor, () -> stopRequested.set(true));
        server.start();
        base = "http://127.0.0.1:" + server.getPort();
    }

    private void startDefault() throws Exception {
        start(null, "policies:\n  - id: admin\n    permissions: [\"*\"]\n  - id: reader\n    permissions: [\"server.ping\", \"players.list\"]\n");
    }

    private SignedHttp token(String id, String policy) {
        TokenManager.IssueResult r = tm.issueTokenWithPickup(id, 0L, null, 300);
        tm.setTokenPolicyId(r.tokenId, policy);
        TokenManager.PickupRecord pr = tm.consumePickup(r.pickupId, r.passkey);
        return new SignedHttp(base, r.tokenId, pr.secret);
    }

    @BeforeEach
    void reset() {
        stopRequested.set(false);
        logged.clear();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    @Test
    public void pingAndProtocolHeader() throws Exception {
        startDefault();
        SignedHttp admin = token("admin1", "admin");
        SignedHttp.Result r = admin.get("/ping");
        assertEquals(200, r.status, r.body);
        assertTrue(r.json().get("ok").getAsBoolean());
        assertEquals(String.valueOf(BridgeServer.PROTOCOL_VERSION), r.header("X-HungerBridge-Protocol"));
        JsonObject meta = admin.get("/server/meta").json();
        assertEquals(BridgeServer.PROTOCOL_VERSION, meta.get("protocol_version").getAsInt());
        assertEquals("test", meta.get("platform").getAsString());
        assertEquals("26.3", meta.get("minecraft_version").getAsString());
    }

    @Test
    public void authFailureReasonsDoNotLeakTokenExistence() throws Exception {
        startDefault();
        SignedHttp admin = token("admin2", "admin");
        long now = Instant.now().getEpochSecond();

        SignedHttp.Result missing = new SignedHttp(base, null, null).send("GET", "/ping", null, null);
        assertEquals(401, missing.status);
        assertEquals("missing_credentials", missing.json().get("reason").getAsString());
        assertNotNull(missing.header("X-HungerBridge-Protocol"));

        SignedHttp ghost = new SignedHttp(base, "no-such-token", "00".repeat(32));
        assertEquals("invalid_credentials", ghost.send("GET", "/ping", null, ghost.sign("GET", "/ping", "")).json().get("reason").getAsString());
        // stale timestamp is reported the same way whether or not the token exists
        assertEquals("timestamp_out_of_range", ghost.send("GET", "/ping", null, ghost.sign("GET", "/ping", "", now - 3600, null)).json().get("reason").getAsString());
        assertEquals("timestamp_out_of_range", admin.send("GET", "/ping", null, admin.sign("GET", "/ping", "", now - 3600, null)).json().get("reason").getAsString());

        SignedHttp wrongSecret = new SignedHttp(base, "admin2", "00".repeat(32));
        assertEquals("invalid_credentials", wrongSecret.send("GET", "/ping", null, wrongSecret.sign("GET", "/ping", "")).json().get("reason").getAsString());

        // expired: only revealed to callers that hold the secret
        tm.listTokens().get("admin2").expiry = now - 10;
        assertEquals("token_expired", admin.get("/ping").json().get("reason").getAsString());
        assertEquals("invalid_credentials", wrongSecret.send("GET", "/ping", null, wrongSecret.sign("GET", "/ping", "")).json().get("reason").getAsString());
    }

    @Test
    public void nonceReplayIsRejectedButOnlyAfterSignatureCheck() throws Exception {
        startDefault();
        SignedHttp admin = token("admin3", "admin");
        SignedHttp.Signed s = admin.sign("GET", "/ping", "");
        // a forged request reusing the nonce must not burn it
        SignedHttp.Signed forged = new SignedHttp.Signed(s.tokenId, s.timestamp, s.nonce, "00".repeat(32));
        assertEquals(401, admin.send("GET", "/ping", null, forged).status);
        assertEquals(200, admin.send("GET", "/ping", null, s).status);
        SignedHttp.Result replay = admin.send("GET", "/ping", null, s);
        assertEquals(401, replay.status);
        assertEquals("nonce_replay", replay.json().get("reason").getAsString());
    }

    @Test
    public void tamperedBodyIsRejected() throws Exception {
        startDefault();
        SignedHttp admin = token("admin4", "admin");
        SignedHttp.Signed s = admin.sign("POST", "/server/log", "{\"message\":\"a\"}");
        assertEquals(401, admin.send("POST", "/server/log", "{\"message\":\"b\"}", s).status);
    }

    @Test
    public void rateLimitConsumesOneTokenPerRequestAndDoesNotBurnNonce() throws Exception {
        start("rate-limits:\n  per-ip:\n    burst: 100\n    rps: 100\n  per-token:\n    burst: 3\n    rps: 1\n",
                "policies:\n  - id: admin\n    permissions: [\"*\"]\n");
        SignedHttp admin = token("rl", "admin");
        // Regression: handlers rate-limited a second time, so only ~half of the burst was usable.
        for (int i = 0; i < 3; i++) assertEquals(200, admin.get("/ping").status, "request " + i);
        SignedHttp.Signed s = admin.sign("GET", "/ping", "");
        SignedHttp.Result limited = admin.send("GET", "/ping", null, s);
        assertEquals(429, limited.status);
        assertEquals("rate_limited", limited.json().get("error").getAsString());
        assertNotNull(limited.header("Retry-After"));
        assertTrue(Integer.parseInt(limited.header("Retry-After")) >= 1);
        // Regression: the nonce of a rate-limited request used to be consumed, so retrying
        // the identical request failed with a replay error.
        Thread.sleep(1200);
        assertEquals(200, admin.send("GET", "/ping", null, s).status);
    }

    @Test
    public void aclAndDefaultPolicyTemplate() throws Exception {
        start(null, null); // seeds autogen templates (policies.yaml "default")
        SignedHttp def = token("def", "default");
        // Regression: the shipped default policy granted nodes that no endpoint checks.
        for (String p : List.of("/ping", "/server/meta", "/players/list", "/world/tps", "/world/time", "/system/memory")) {
            assertEquals(200, def.get(p).status, p);
        }
        assertEquals(403, def.post("/server/run", "{\"command\":\"list\"}").status);
        assertEquals(403, def.post("/server/log", "{\"message\":\"x\"}").status);
        assertEquals(403, def.get("/server/stream").status);
    }

    @Test
    public void legacyPermissionNodesAreAliased() throws Exception {
        start(null, "policies:\n  - id: legacy\n    permissions: [\"server.info\", \"player.list\", \"world.read\"]\n");
        SignedHttp legacy = token("legacy", "legacy");
        for (String p : List.of("/ping", "/server/meta", "/players/list", "/world/tps", "/world/chunks")) {
            assertEquals(200, legacy.get(p).status, p);
        }
        assertEquals(403, legacy.get("/system/memory").status);
        JsonObject check = legacy.get("/auth/check").json();
        assertTrue(check.getAsJsonArray("permissions").toString().contains("players.list"), check.toString());
        assertTrue(logged.stream().anyMatch(l -> l.contains("legacy permission node")), logged.toString());
    }

    @Test
    public void authCheckReportsEffectivePermissionsAndRejectsRevoked() throws Exception {
        startDefault();
        SignedHttp reader = token("rd", "reader");
        JsonObject check = reader.get("/auth/check").json();
        assertEquals("rd", check.get("tokenId").getAsString());
        assertEquals("reader", check.get("policyId").getAsString());
        assertEquals("[\"server.ping\",\"players.list\"]", check.getAsJsonArray("permissions").toString());
        tm.revokeToken("rd");
        SignedHttp.Result revoked = reader.get("/auth/check");
        assertEquals(401, revoked.status);
        assertEquals("token_revoked", revoked.json().get("reason").getAsString());
    }

    @Test
    public void unknownPathsAndMethodsAreJson() throws Exception {
        startDefault();
        SignedHttp admin = token("admin5", "admin");
        SignedHttp.Result nope = admin.get("/nope");
        assertEquals(404, nope.status);
        assertEquals("not_found", nope.json().get("error").getAsString());
        assertEquals(404, admin.get("/ping/extra").status);
        SignedHttp.Result wrongMethod = admin.post("/ping", "{}");
        assertEquals(405, wrongMethod.status);
        assertFalse(wrongMethod.json().get("ok").getAsBoolean());
    }

    @Test
    public void badRequestsAreReportedInsteadOfDroppingTheConnection() throws Exception {
        startDefault();
        SignedHttp admin = token("admin6", "admin");
        assertEquals(400, admin.post("/server/log", "{\"level\":null}").status);
        assertEquals(400, admin.post("/server/log", "{\"message\":{\"nested\":true}}").status);
        assertEquals(400, admin.post("/server/run", "{\"command\":\"\"}").status);
        SignedHttp.Signed s = admin.sign("POST", "/server/run", "not json");
        assertEquals(400, admin.send("POST", "/server/run", "not json", s).status);
        String huge = "{\"message\":\"" + "x".repeat(1024 * 1024 + 10) + "\"}";
        assertEquals(413, admin.post("/server/log", huge).status);
    }

    @Test
    public void runLogAndWorldEndpoints() throws Exception {
        startDefault();
        SignedHttp admin = token("admin7", "admin");
        SignedHttp.Result run = admin.post("/server/run", "{\"command\":\"say \\ud83d\\ude00 caf\\u00e9\",\"show_console\":true,\"silent\":false}");
        assertEquals(200, run.status, run.body);
        assertEquals("say 😀 café", executor.executed.get(0));
        assertEquals("[Server] 😀 café", run.json().getAsJsonArray("output").get(0).getAsString());
        assertFalse(admin.post("/server/run", "{\"command\":\"list\",\"silent\":true}").json().has("output"));

        assertEquals(200, admin.post("/server/log", "{\"level\":\"BRIDGE\",\"message\":\"hello\",\"thread\":\"worker\"}").status);
        assertTrue(logged.contains("BRIDGE|worker|hello"), logged.toString());

        JsonObject time = admin.get("/world/time").json();
        long full = time.get("full_time").getAsLong();
        assertEquals(full % 24000L, time.get("time").getAsLong());
        assertEquals(full / 24000L, time.get("day").getAsLong());
        assertTrue(Math.abs(time.get("server_time").getAsLong() - Instant.now().getEpochSecond()) < 5);

        executor.players = List.of("a", "b", "c");
        JsonObject players = admin.get("/players/list").json();
        assertEquals(3, players.get("count").getAsInt());
        assertEquals(2, players.getAsJsonArray("players").size(), "players.max-list must cap the list");
        assertEquals(20, players.get("max").getAsInt());

        JsonObject chunks = admin.get("/world/chunks").json();
        assertEquals(441, chunks.get("total").getAsInt());
        JsonObject tps = admin.get("/world/tps").json();
        assertEquals(19.7, tps.get("tps_15m").getAsDouble());
        assertEquals("clear", admin.get("/world/weather").json().get("weather").getAsString());

        JsonObject stop = admin.post("/server/stop", "{}").json();
        assertTrue(stop.get("stopped").getAsBoolean());
        assertTrue(stopRequested.get());
    }

    @Test
    public void streamSendsHistoryThenMarkerThenLiveLines() throws Exception {
        startDefault();
        SignedHttp admin = token("admin8", "admin");
        LogDistributor.get().publish("old-1");
        LogDistributor.get().publish("old-2");
        LogDistributor.get().publish("old-3");
        try (SseReader sse = SseReader.open(admin, "/server/stream?history=2")) {
            assertEquals("data:old-2", sse.nextNonEmpty());
            assertEquals("data:old-3", sse.nextNonEmpty());
            assertEquals(":history-end", sse.nextNonEmpty());
            LogDistributor.get().publish("multi\nline pickup passkey=abcdef");
            assertEquals("data:multi\\nline pickup passkey=[REDACTED]", sse.nextNonEmpty());
        }
    }

    @Test
    public void streamWithoutHistoryFlushesMarkerImmediately() throws Exception {
        startDefault();
        SignedHttp admin = token("admin9", "admin");
        long t0 = System.nanoTime();
        try (SseReader sse = SseReader.open(admin, "/server/stream")) {
            assertEquals(":history-end", sse.nextNonEmpty());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 3000);
        }
    }

    @Test
    public void manyOpenStreamsDoNotStarveOtherRequests() throws Exception {
        // Regression: a 4-thread pool with a bounded queue let 4 SSE clients block every other request.
        startDefault();
        SignedHttp admin = token("admin10", "admin");
        List<SseReader> streams = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                SseReader r = SseReader.open(admin, "/server/stream");
                assertEquals(":history-end", r.nextNonEmpty());
                streams.add(r);
            }
            CompletableFuture<Integer> ping = CompletableFuture.supplyAsync(() -> {
                try { return admin.get("/ping").status; } catch (Exception e) { throw new RuntimeException(e); }
            });
            assertEquals(200, ping.get(5, TimeUnit.SECONDS));
        } finally {
            for (SseReader r : streams) r.close();
        }
    }

    @Test
    public void concurrentRequestsNeverShareAuthState() throws Exception {
        // Regression: the authenticated token was stored in HttpExchange attributes, which the
        // JDK backs with the per-context (per-endpoint) map shared by concurrent requests.
        // A reader racing an admin on /server/run could pass the ACL with the admin's token.
        startDefault();
        SignedHttp admin = token("race-admin", "admin");
        SignedHttp reader = token("race-reader", "reader");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        try {
            List<java.util.concurrent.Future<int[]>> futures = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                final boolean asAdmin = i % 2 == 0;
                futures.add(pool.submit(() -> {
                    SignedHttp who = asAdmin ? admin : reader;
                    return new int[] {asAdmin ? 1 : 0, who.post("/server/run", "{\"command\":\"list\"}").status};
                }));
            }
            int adminOk = 0;
            int readerOk = 0;
            for (var f : futures) {
                int[] r = f.get(60, TimeUnit.SECONDS);
                if (r[0] == 1) {
                    assertEquals(200, r[1], "admin request failed");
                    adminOk++;
                } else {
                    assertEquals(403, r[1], "reader must never be allowed to run commands");
                    readerOk++;
                }
            }
            assertEquals(100, adminOk);
            assertEquals(100, readerOk);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Line reader over a raw SSE response body. */
    static final class SseReader implements AutoCloseable {
        private final InputStream in;
        private final BufferedReader reader;

        private SseReader(InputStream in) {
            this.in = in;
            this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        static SseReader open(SignedHttp http, String pathAndQuery) throws Exception {
            String path = pathAndQuery.split("\\?", 2)[0];
            SignedHttp.Signed s = http.sign("GET", path, "");
            HttpRequest req = HttpRequest.newBuilder(URI.create(http.base() + pathAndQuery))
                    .header("X-Auth-Id", s.tokenId).header("X-Auth-Timestamp", s.timestamp)
                    .header("X-Auth-Nonce", s.nonce).header("X-Auth-Signature", s.signature)
                    .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<InputStream> resp = http.client().sendAsync(req, HttpResponse.BodyHandlers.ofInputStream()).get(5, TimeUnit.SECONDS);
            assertEquals(200, resp.statusCode());
            assertTrue(resp.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
            return new SseReader(resp.body());
        }

        String nextNonEmpty() throws Exception {
            CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isEmpty()) return line;
                    }
                    return null;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            return f.get(10, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            try { in.close(); } catch (Exception ignored) {}
        }
    }
}
