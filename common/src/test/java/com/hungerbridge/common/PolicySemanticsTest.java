package com.hungerbridge.common;

import com.hungerbridge.common.security.TokenManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public final class PolicySemanticsTest {

    @Test
    public void emptyBlacklistPolicyProducesEmptyRuntimeBlacklist() throws IOException {
        Path dir = Files.createTempDirectory("hb-policy-blacklist");
        Files.writeString(dir.resolve("config.yaml"), "port: 1913\n");
        Files.writeString(dir.resolve("security.yaml"), "ip_list:\n  mode: blacklist\n  list: []\nrate_limits:\n  token_rps: 5\n  token_burst: 10\n  ip_rps: 20\n  ip_burst: 40\n audit_retention_days: 14\n");
        Files.writeString(dir.resolve("tokens.yaml"), "tokens:\n  - id: admin\n    default_expiry: 0\n    max_skew: -1\n    endpoints_mode: blacklist\n    endpoints: []\n    commands_mode: blacklist\n    commands: []\n");

        Config config = Config.load(dir, (l, m) -> {});
        TokenManager tm = new TokenManager(dir, null);
        config.setTokenManager(tm);
        AdminService admin = new AdminService(dir, config, null, null);

        TokenManager.Token t = admin.createToken("admin", null, 0L, null, null);
        assertNotNull(t);
        assertNotNull(t.blacklist);
        assertTrue(t.blacklist.isEmpty(), "Expected runtime blacklist to be an explicit empty list (allow all)");
    }

    @Test
    public void emptyWhitelistPolicyProducesEmptyRuntimeWhitelist() throws IOException {
        Path dir = Files.createTempDirectory("hb-policy-whitelist");
        Files.writeString(dir.resolve("config.yaml"), "port: 1913\n");
        Files.writeString(dir.resolve("security.yaml"), "ip_list:\n  mode: blacklist\n  list: []\nrate_limits:\n  token_rps: 5\n  token_burst: 10\n  ip_rps: 20\n  ip_burst: 40\n audit_retention_days: 14\n");
        Files.writeString(dir.resolve("tokens.yaml"), "tokens:\n  - id: limited\n    default_expiry: 0\n    max_skew: -1\n    endpoints_mode: whitelist\n    endpoints: []\n    commands_mode: whitelist\n    commands: []\n");

        Config config = Config.load(dir, (l, m) -> {});
        TokenManager tm = new TokenManager(dir, null);
        config.setTokenManager(tm);
        AdminService admin = new AdminService(dir, config, null, null);

        TokenManager.Token t = admin.createToken("limited", null, 0L, null, null);
        assertNotNull(t);
        assertNotNull(t.whitelist);
        assertTrue(t.whitelist.isEmpty(), "Expected runtime whitelist to be an explicit empty list (deny all)");
    }
}
