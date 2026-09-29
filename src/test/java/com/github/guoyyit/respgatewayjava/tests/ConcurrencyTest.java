package com.github.guoyyit.respgatewayjava.tests;

import com.github.guoyyit.respgatewayjava.shard.Shard;
import com.github.guoyyit.respgatewayjava.tests.support.GatewayTestHarness;
import com.github.guoyyit.respgatewayjava.tests.support.RespTestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void pipelinedCommandsAreRepliedInArrivalOrder() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir);
             RespTestClient client = new RespTestClient(h.port())) {
            for (int i = 0; i < 200; i++) {
                client.sendCommand("SET", "{pipe}k" + i, "v" + i);
            }
            for (int i = 0; i < 200; i++) {
                assertEquals("OK", client.readReply());
            }
            for (int i = 0; i < 200; i++) {
                client.sendCommand("GET", "{pipe}k" + i);
            }
            for (int i = 0; i < 200; i++) {
                assertEquals("v" + i, client.readReply());
            }
        }
    }

    @Test
    void multipleConcurrentClientsDoNotBlockEachOther() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir)) {
            int clients = 8;
            int commandsPerClient = 100;
            ExecutorService pool = Executors.newFixedThreadPool(clients);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int clientId = 0; clientId < clients; clientId++) {
                final int id = clientId;
                futures.add(pool.submit(() -> {
                    start.await();
                    try (RespTestClient client = new RespTestClient(h.port())) {
                        for (int i = 0; i < commandsPerClient; i++) {
                            String key = "{c" + id + "}i" + i;
                            client.sendCommand("INCR", key);
                            assertEquals(1L, client.readReply());
                            client.sendCommand("GET", key);
                            assertEquals("1", client.readReply());
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            pool.shutdownNow();
        }
    }

    @Test
    void slowShardDoesNotBlockOtherShardsOrConnections() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir, 4);
             RespTestClient slowClient = new RespTestClient(h.port());
             RespTestClient fastClient = new RespTestClient(h.port())) {
            int slowShard = pickTwoDistinctShards(h)[0];
            int fastShard = pickTwoDistinctShards(h)[1];
            String slowKey = findKeyForShard(h, slowShard);
            String fastKey = findKeyForShard(h, fastShard);

            Shard shard = h.shardManager().getShards().get(slowShard);
            CountDownLatch blockerStarted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            shard.submit(() -> {
                blockerStarted.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

            slowClient.sendCommand("GET", slowKey);
            long begin = System.nanoTime();
            fastClient.sendCommand("SET", fastKey, "fast");
            assertEquals("OK", fastClient.readReply());
            fastClient.sendCommand("GET", fastKey);
            assertEquals("fast", fastClient.readReply());
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
            assertTrue(elapsedMs < 1000, "fast shard command blocked for " + elapsedMs + "ms");

            release.countDown();
            Object slowReply = slowClient.readReply();
            assertTrue(slowReply == null || slowReply.equals("null bulk"));
        }
    }

    private static int[] pickTwoDistinctShards(GatewayTestHarness h) {
        return new int[]{0, 1};
    }

    private static String findKeyForShard(GatewayTestHarness h, int shardIndex) {
        for (int i = 0; i < 100_000; i++) {
            String key = "slow-" + shardIndex + '-' + i;
            if (h.shardManager().shardIndexForKey(key) == shardIndex) {
                return key;
            }
        }
        throw new IllegalStateException("no key for shard " + shardIndex);
    }
}
