package com.hungerbridge.common;

import com.hungerbridge.common.security.TokenManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class TokenManagerPickupTest {

    @Test
    public void issueAndConsumePickup() throws Exception {
        Path dir = Files.createTempDirectory("hb-token-test");
        TokenManager tm = new TokenManager(dir, (l,m)->{});

        TokenManager.IssueResult res = tm.issueTokenWithPickup(null, 0, null, null, 300);
        assertNotNull(res, "IssueResult should not be null");
        assertNotNull(res.pickupId);

        TokenManager.PickupRecord pr = tm.consumePickup(res.pickupId);
        assertNotNull(pr, "Pickup should be consumable immediately after issue");
        assertEquals(res.tokenId, pr.tokenId);

        // second consume should return null
        TokenManager.PickupRecord pr2 = tm.consumePickup(res.pickupId);
        assertNull(pr2, "Pickup should be consumed only once");
    }
}
