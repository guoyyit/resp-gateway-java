package com.github.guoyyit.respgatewayjava.tests;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpiryTest {

    @Test
    void expiredKeyReturnsNilEvenWithoutAccess() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            client.sendCommand("SET", "soon", "v", "PX", "30");
            assertEquals("OK", client.readReply().stringValue());

            client.sendCommand("EXISTS", "soon");
            assertEquals(1L, client.readReply().longValue());

            Thread.sleep(120L);

            client.sendCommand("GET", "soon");
            assertTrue(client.readReply().isNull());
            client.sendCommand("EXISTS", "soon");
            assertEquals(0L, client.readReply().longValue());
            client.sendCommand("TTL", "soon");
            assertEquals(-2L, client.readReply().longValue());
        }
    }

    @Test
    void backgroundCleanerReclaimsExpiredEntries() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            client.sendCommand("SET", "temp", "v", "PX", "30");
            client.readReply();
            int shardIndex = gateway.service().registry().router()
                    .shardIndex("temp".getBytes());

            assertTrue(gateway.service().shard(shardIndex).storeForTest().size() >= 1);
            Thread.sleep(500L);

            assertEquals(0, gateway.service().shard(shardIndex).storeForTest().size());
        }
    }

    @Test
    void incrKeepsRemainingTtl() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            client.sendCommand("SET", "counter", "10", "PX", "2000");
            client.readReply();
            client.sendCommand("INCR", "counter");
            assertEquals(11L, client.readReply().longValue());
            client.sendCommand("TTL", "counter");
            long ttl = client.readReply().longValue();
            assertTrue(ttl > 1L && ttl <= 2L, "ttl should remain, got " + ttl);
        }
    }

    @Test
    void expiredKeyCanBeRecreated() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            client.sendCommand("SET", "k", "old", "PX", "30");
            client.readReply();
            Thread.sleep(100L);
            client.sendCommand("APPEND", "k", "new");
            assertEquals(3L, client.readReply().longValue());
            client.sendCommand("GET", "k");
            assertEquals("new", RespTestClient.bulkAsText(client.readReply()));
        }
    }
}
