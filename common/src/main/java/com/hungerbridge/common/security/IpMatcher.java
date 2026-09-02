package com.hungerbridge.common.security;

import java.net.InetAddress;
import java.util.StringTokenizer;

/**
 * Minimal IP/CIDR matcher supporting IPv4 CIDR (e.g. 192.0.2.0/24) and exact IPs.
 */
public final class IpMatcher {
    public static boolean matches(String pattern, String ip) {
        if (pattern == null || ip == null) return false;
        pattern = pattern.trim();
        try {
            if (pattern.contains("/")) {
                StringTokenizer st = new StringTokenizer(pattern, "/");
                String base = st.nextToken();
                int prefix = Integer.parseInt(st.nextToken());
                byte[] baseBytes = InetAddress.getByName(base).getAddress();
                byte[] ipBytes = InetAddress.getByName(ip).getAddress();

                // prefix must be within bounds for the address family
                int maxBits = baseBytes.length * 8;
                if (prefix < 0 || prefix > maxBits) return false;

                int fullBytes = prefix / 8;
                int remBits = prefix % 8;

                // lengths must match (no automatic IPv4-mapped handling)
                if (baseBytes.length != ipBytes.length) return false;

                // compare full bytes
                for (int i = 0; i < fullBytes; i++) {
                    if ((baseBytes[i] & 0xFF) != (ipBytes[i] & 0xFF)) return false;
                }

                if (remBits > 0) {
                    int mask = (0xFF << (8 - remBits)) & 0xFF;
                    int idx = fullBytes;
                    if (idx >= baseBytes.length) return false;
                    if (((baseBytes[idx] & mask) != (ipBytes[idx] & mask))) return false;
                }

                return true;
            } else {
                return InetAddress.getByName(pattern).getHostAddress().equals(InetAddress.getByName(ip).getHostAddress());
            }
        } catch (Exception e) {
            return false;
        }
    }
}
