package com.hungerbridge.common.security;

import com.hungerbridge.common.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple token-bucket rate limiter supporting per-key buckets.
 */
public final class RateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Logger logger;

    // defaults (can be made configurable later)
    private double defaultRatePerSecond = 5.0; // tokens/sec
    private double defaultBurst = 10.0; // max tokens
    private double defaultIpRatePerSecond = 20.0;
    private double defaultIpBurst = 40.0;

    public RateLimiter(Path configDir, Logger logger) {
        this.logger = logger;
        try {
            Path logs = configDir.resolve("logs");
            if (!Files.exists(logs)) Files.createDirectories(logs);
        } catch (IOException e) {
            logger.log("WARN", "Failed to ensure logs dir for rate limiter: " + e.getMessage());
        }
    }

    private static final class Bucket {
        double tokens;
        double capacity;
        double refillRate; // tokens per second
        long lastRefillEpochMs;
    }

    private Bucket getBucket(String key, boolean isIp) {
        return buckets.computeIfAbsent(key, k -> {
            Bucket b = new Bucket();
            if (isIp) {
                b.capacity = defaultIpBurst;
                b.refillRate = defaultIpRatePerSecond;
                b.tokens = b.capacity;
            } else {
                b.capacity = defaultBurst;
                b.refillRate = defaultRatePerSecond;
                b.tokens = b.capacity;
            }
            b.lastRefillEpochMs = System.currentTimeMillis();
            return b;
        });
    }

    public void setLimits(double tokenRps, double tokenBurst, double ipRps, double ipBurst) {
        this.defaultRatePerSecond = tokenRps;
        this.defaultBurst = tokenBurst;
        this.defaultIpRatePerSecond = ipRps;
        this.defaultIpBurst = ipBurst;
    }

    public double getTokenRps() { return defaultRatePerSecond; }
    public double getTokenBurst() { return defaultBurst; }
    public double getIpRps() { return defaultIpRatePerSecond; }
    public double getIpBurst() { return defaultIpBurst; }

    public synchronized boolean allowRequestForToken(String tokenId) {
        if (tokenId == null) return true; // treat anonymous as allowed for token check
        Bucket b = getBucket("token:" + tokenId, false);
        refill(b);
        if (b.tokens >= 1.0) {
            b.tokens -= 1.0;
            return true;
        }
        return false;
    }

    public synchronized boolean allowRequestForIp(String ip) {
        if (ip == null) return true;
        Bucket b = getBucket("ip:" + ip, true);
        refill(b);
        if (b.tokens >= 1.0) {
            b.tokens -= 1.0;
            return true;
        }
        return false;
    }

    private void refill(Bucket b) {
        long nowMs = System.currentTimeMillis();
        long deltaMs = nowMs - b.lastRefillEpochMs;
        if (deltaMs <= 0) return;
        double add = (deltaMs / 1000.0) * b.refillRate;
        b.tokens = Math.min(b.capacity, b.tokens + add);
        b.lastRefillEpochMs = nowMs;
    }
}
