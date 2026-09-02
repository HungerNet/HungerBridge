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
                if (baseBytes.length != ipBytes.length) return false;
                int bits = prefix;
                for (int i = 0; i < baseBytes.length; i++) {
                    int mask = 0;
                    if (bits >= 8) mask = 0xFF;
                    else if (bits > 0) mask = (~0) << (8 - bits) & 0xFF;
                    else mask = 0;
                    if ((baseBytes[i] & mask) != (ipBytes[i] & mask)) return false;
                    bits -= 8;
                    if (bits < 0) bits = 0;
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
