package com.hungerbridge.common.testing;

import com.hungerbridge.common.BridgeServer;
import com.hungerbridge.common.Config;
import com.hungerbridge.common.LogDistributor;
import com.hungerbridge.common.Logger;
import com.hungerbridge.common.log.LogLineFormatter;
import com.hungerbridge.common.security.TokenManager;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the real HungerBridge HTTP stack with a fake Minecraft backend so that clients
 * (HungerBridgePy) can run their live protocol suite without a game server:
 *
 * <pre>./gradlew :common:standaloneBridge -Phb.standalone.port=19130</pre>
 *
 * Prints {@code PICKUP_URL=...} for an admin token once ready. {@code POST /server/stop}
 * terminates the process, like it stops a real server.
 */
public final class StandaloneBridge {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("hb.standalone.port", "19130"));
        Path dir = Files.createTempDirectory("hb-standalone");
        Files.writeString(dir.resolve("config.yaml"), "port: " + port + "\nbind-address: 127.0.0.1\nplayers:\n  max-list: 50\n");
        Files.writeString(dir.resolve("security.yaml"), "rate-limits:\n  per-ip:\n    burst: 1000\n    rps: 1000\n  per-token:\n    burst: 50\n    rps: 10\n");
        Files.writeString(dir.resolve("policies.yaml"), "policies:\n  - id: admin\n    permissions: [\"*\"]\n");

        Logger logger = (level, thread, message) -> {
            String line = LogLineFormatter.format(System.currentTimeMillis(), level == null ? "INFO" : level, message, null);
            System.out.println(line);
            LogDistributor.get().publish(line);
        };
        Config config = Config.load(dir, logger);
        TokenManager tm = new TokenManager(dir, logger);
        config.setTokenManager(tm);
        config.setPlatform("standalone");
        config.setMinecraftVersion("standalone");
        config.setBridgeVersion("standalone");

        FakeExecutor executor = new FakeExecutor() {
            @Override
            public java.util.List<String> executeWithOutput(String command, boolean showConsole) {
                java.util.List<String> out = super.executeWithOutput(command, showConsole);
                if (showConsole) out.forEach(l -> logger.log("INFO", "Server thread", l));
                return out;
            }
        };
        BridgeServer[] holder = new BridgeServer[1];
        holder[0] = new BridgeServer(dir, config, logger, executor, () -> new Thread(() -> {
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
            holder[0].stop();
            System.exit(0);
        }, "standalone-stop").start());
        holder[0].start();

        TokenManager.IssueResult r = tm.issueTokenWithPickup("standalone-admin", 0L, null, 3600);
        tm.setTokenPolicyId(r.tokenId, "admin");
        System.out.println("PICKUP_URL=http://127.0.0.1:" + holder[0].getPort() + "/pickup/" + r.pickupId + "?passkey=" + r.passkey);
        System.out.flush();
        Thread.currentThread().join();
    }

    private StandaloneBridge() {}
}
