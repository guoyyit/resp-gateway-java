package com.github.guoyyit.respgatewayjava.tests;

import com.github.guoyyit.respgatewayjava.tests.support.GatewayTestHarness;
import com.github.guoyyit.respgatewayjava.tests.support.RespTestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceRestartTest {

    @TempDir
    Path tempDir;

    @Test
    void dataShardCountAndTtlSurviveRestart() throws Exception {
        try (GatewayTestHarness first = GatewayTestHarness.start(tempDir, 3);
             RespTestClient client = new RespTestClient(first.port())) {
            client.sendCommand("SET", "name", "gateway");
            assertEquals("OK", client.readReply());
            client.sendCommand("SET", "counter", "41");
            assertEquals("OK", client.readReply());
            client.sendCommand("INCR", "counter");
            assertEquals(42L, client.readReply());
            client.sendCommand("SET", "kept-ttl", "v", "PX", "600000");
            assertEquals("OK", client.readReply());
            client.sendCommand("SET", "short", "v", "PX", "30");
            assertEquals("OK", client.readReply());
            first.save();
        }

        Thread.sleep(60);

        try (GatewayTestHarness second = GatewayTestHarness.start(tempDir, 3);
             RespTestClient client = new RespTestClient(second.port())) {
            assertEquals(3, second.shardManager().getShardCount());

            client.sendCommand("GET", "name");
            assertEquals("gateway", client.readReply());
            client.sendCommand("GET", "counter");
            assertEquals("42", client.readReply());
            client.sendCommand("INCR", "counter");
            assertEquals(43L, client.readReply());

            client.sendCommand("GET", "kept-ttl");
            assertEquals("v", client.readReply());
            client.sendCommand("TTL", "kept-ttl");
            long ttl = (Long) client.readReply();
            assertTrue(ttl > 0 && ttl <= 600, "ttl must survive restart, was " + ttl);

            client.sendCommand("GET", "short");
            assertNull(client.readReply());
        }
    }

    @Test
    void topologyChangeIsPersistedAndRejectsOldEpochCommands() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir, 2);
             RespTestClient client = new RespTestClient(h.port())) {
            client.sendCommand("SET", "before", "1");
            assertEquals("OK", client.readReply());

            String inflightKey = findKeyForShard(h, 0);
            java.util.concurrent.CountDownLatch blockerStarted = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            h.shardManager().getShards().get(0).submit(() -> {
                blockerStarted.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            assertTrue(blockerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));

            client.sendCommand("GET", inflightKey);
            Thread.sleep(100);
            h.admin().reshard(3);
            release.countDown();
            Object reply = client.readReply();
            assertTrue(reply instanceof com.github.guoyyit.respgatewayjava.tests.support.RespTestClient.ErrorReply
                            error && error.message().contains("topology"),
                    "commands routed against old topology must be rejected, got " + reply);

            client.sendCommand("PING");
            assertEquals("PONG", client.readReply());
        }

        try (GatewayTestHarness restarted = GatewayTestHarness.start(tempDir, 3);
             RespTestClient client = new RespTestClient(restarted.port())) {
            assertEquals(3, restarted.shardManager().getShardCount());
            client.sendCommand("PING");
            assertEquals("PONG", client.readReply());
        }
    }

    private static String findKeyForShard(GatewayTestHarness h, int shardIndex) {
        for (int i = 0; i < 100_000; i++) {
            String key = "topo-" + shardIndex + '-' + i;
            if (h.shardManager().shardIndexForKey(key) == shardIndex) {
                return key;
            }
        }
        throw new IllegalStateException("no key for shard " + shardIndex);
    }
}
