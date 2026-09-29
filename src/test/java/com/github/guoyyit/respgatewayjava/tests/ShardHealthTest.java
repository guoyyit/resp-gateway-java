package com.github.guoyyit.respgatewayjava.tests;

import com.github.guoyyit.respgatewayjava.shard.Shard;
import com.github.guoyyit.respgatewayjava.tests.support.GatewayTestHarness;
import com.github.guoyyit.respgatewayjava.tests.support.RespTestClient;
import com.github.guoyyit.respgatewayjava.tests.support.RespTestClient.ErrorReply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardHealthTest {

    @TempDir
    Path tempDir;

    @Test
    void faultedShardReturnsErrorAndAutoRecovers() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir, 4, 100, 60_000);
             RespTestClient client = new RespTestClient(h.port())) {
            String key0 = findKeyForShard(h, 0);
            String key1 = findKeyForShard(h, 1);

            client.sendCommand("SET", key0, "before");
            client.sendCommand("SET", key1, "other");
            readOk(client, 2);

            h.admin().setShardFault(0, true);
            waitUntil(() -> !h.shardManager().getShards().get(0).isHealthy(), 2000);

            client.sendCommand("GET", key0);
            Object down = client.readReply();
            assertTrue(down instanceof ErrorReply error && error.message().startsWith("CLUSTERDOWN"),
                    "expected CLUSTERDOWN, got " + down);

            client.sendCommand("GET", key1);
            Object other = client.readReply();
            assertTrue(other.equals("other"), "healthy shard must keep serving, got " + other);

            h.admin().setShardFault(0, false);
            waitUntil(() -> h.shardManager().getShards().get(0).isHealthy(), 2000);
            client.sendCommand("GET", key0);
            Object recovered = client.readReply();
            assertTrue(recovered.equals("before"), "data must survive outage, got " + recovered);

            client.sendCommand("PING");
            Object ping = client.readReply();
            assertTrue("PONG".equals(ping));
        }
    }

    @Test
    void saturatedShardQueueReturnsBusyErrorWithoutLosingLaterCommands() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir, 4, 8, 1000, 60_000);
             RespTestClient blocked = new RespTestClient(h.port());
             RespTestClient other = new RespTestClient(h.port())) {
            Shard shard0 = h.shardManager().getShards().get(0);
            String blockedKey = findKeyForShard(h, 0);
            String otherKey = findKeyForShard(h, 1);

            shard0.setSlowMillis(300);
            int capacity = h.properties().getShardQueueCapacity();
            for (int i = 0; i < capacity * 4; i++) {
                blocked.sendCommand("INCR", blockedKey);
            }
            boolean sawBusy = false;
            for (int i = 0; i < capacity * 4; i++) {
                Object reply = blocked.readReply();
                if (reply instanceof ErrorReply error && error.message().startsWith("BUSY")) {
                    sawBusy = true;
                }
            }
            assertTrue(sawBusy, "expected at least one BUSY when the shard queue is full");

            other.sendCommand("SET", otherKey, "alive");
            Object reply = other.readReply();
            assertTrue("OK".equals(reply), "other shard must keep serving, got " + reply);

            shard0.setSlowMillis(0);
            waitUntil(() -> shard0.queueSize() == 0, 5000);
            blocked.sendCommand("SET", blockedKey, "final");
            Object finalReply = blocked.readReply();
            assertTrue("OK".equals(finalReply), "commands after recovery must complete, got " + finalReply);
        }
    }

    private static void readOk(RespTestClient client, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            client.readReply();
        }
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("condition not met within " + timeoutMs + "ms");
    }

    private static String findKeyForShard(GatewayTestHarness h, int shardIndex) {
        for (int i = 0; i < 100_000; i++) {
            String key = "health-" + shardIndex + '-' + i;
            if (h.shardManager().shardIndexForKey(key) == shardIndex) {
                return key;
            }
        }
        throw new IllegalStateException("no key for shard " + shardIndex);
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
