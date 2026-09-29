package com.github.guoyyit.respgatewayjava.tests;

import com.github.guoyyit.respgatewayjava.resp.RespData;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineConcurrencyTest {

    @Test
    void repliesStayInOrderUnderPipelining() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            int count = 200;
            for (int index = 0; index < count; index++) {
                client.sendCommand("SET", "k" + index, Integer.toString(index));
            }
            for (int index = 0; index < count; index++) {
                RespData reply = client.readReply();
                assertEquals("OK", reply.stringValue(), "reply " + index + " out of order");
            }

            for (int index = 0; index < count; index++) {
                client.sendCommand("GET", "k" + index);
            }
            for (int index = 0; index < count; index++) {
                RespData reply = client.readReply();
                assertEquals(Integer.toString(index),
                        RespTestClient.bulkAsText(reply), "get reply " + index + " out of order");
            }
        }
    }

    @Test
    void concurrentConnectionsDoNotBlockEachOther() throws Exception {
        try (TestGateway gateway = TestGateway.start()) {
            int clients = 8;
            int perClient = 50;
            ExecutorService pool = Executors.newFixedThreadPool(clients);
            List<Future<?>> futures = new ArrayList<>();
            for (int clientIndex = 0; clientIndex < clients; clientIndex++) {
                final int owner = clientIndex;
                futures.add(pool.submit(() -> {
                    try (RespTestClient client = new RespTestClient(gateway.port())) {
                        for (int index = 0; index < perClient; index++) {
                            client.sendCommand("SET", "c" + owner + ":" + index,
                                    Integer.toString(index));
                            assertEquals("OK", client.readReply().stringValue());
                        }
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
            pool.shutdown();
        }
    }

    @Test
    void slowShardDoesNotBlockOtherShards() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient slowClient = new RespTestClient(gateway.port());
             RespTestClient fastClient = new RespTestClient(gateway.port())) {
            String slowKey = "slowKey";
            int slowShard = gateway.service().registry().router()
                    .shardIndex(slowKey.getBytes());
            String fastKey = findKeyOnDifferentShard(gateway, slowShard);

            gateway.service().setShardSlow(slowShard, true);
            try {
                slowClient.sendCommand("SET", slowKey, "v");
                long start = System.currentTimeMillis();
                fastClient.sendCommand("SET", fastKey, "v");
                assertEquals("OK", fastClient.readReply().stringValue());
                long elapsed = System.currentTimeMillis() - start;
                assertTrue(elapsed < 150L,
                        "fast shard was blocked for " + elapsed + "ms");
                assertEquals("OK", slowClient.readReply().stringValue());
            } finally {
                gateway.service().setShardSlow(slowShard, false);
            }
        }
    }

    private String findKeyOnDifferentShard(TestGateway gateway, int avoidedShard) {
        for (int index = 0; index < 100_000; index++) {
            String key = "fast" + index;
            int shard = gateway.service().registry().router().shardIndex(key.getBytes());
            if (shard != avoidedShard) {
                return key;
            }
        }
        throw new IllegalStateException("no key found on a different shard");
    }
}
