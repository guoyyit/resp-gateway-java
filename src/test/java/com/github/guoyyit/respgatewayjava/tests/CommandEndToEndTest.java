package com.github.guoyyit.respgatewayjava.tests;

import com.github.guoyyit.respgatewayjava.resp.RespData;
import com.github.guoyyit.respgatewayjava.resp.RespType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandEndToEndTest {

    @Test
    void getSetDelAndEmptyStringDistinction() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            client.sendCommand("GET", "missing");
            assertTrue(client.readReply().isNull());

            client.sendCommand("SET", "k", "");
            assertEquals("OK", client.readReply().stringValue());

            client.sendCommand("GET", "k");
            RespData empty = client.readReply();
            assertEquals(RespType.BULK_STRING, empty.type());
            assertEquals(0, empty.bulkBytes().length);

            client.sendCommand("DEL", "k");
            assertEquals(1L, client.readReply().longValue());

            client.sendCommand("GET", "k");
            assertTrue(client.readReply().isNull());
        }
    }

    @Test
    void existsAndMultiKeyDel() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            String[] keys = sameSlotKeys(gateway, 3);
            client.sendRaw(RespTestClient.encodeCommand("SET", keys[0], "1"));
            client.sendRaw(RespTestClient.encodeCommand("SET", keys[1], "2"));
            client.readReplies(2);

            client.sendCommand("EXISTS", keys[0], keys[1], keys[2]);
            assertEquals(2L, client.readReply().longValue());

            client.sendCommand("DEL", keys[0], keys[1], keys[2]);
            assertEquals(2L, client.readReply().longValue());
        }
    }

    @Test
    void incrRejectsNonIntegerWithoutMutation() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            client.sendCommand("SET", "n", "abc");
            assertEquals("OK", client.readReply().stringValue());

            client.sendCommand("INCR", "n");
            RespData error = client.readReply();
            assertEquals(RespType.ERROR, error.type());
            assertTrue(error.stringValue().startsWith("WRONGTYPE"));

            client.sendCommand("GET", "n");
            assertEquals("abc", RespTestClient.bulkAsText(client.readReply()));

            client.sendCommand("INCR", "counter");
            assertEquals(1L, client.readReply().longValue());
            client.sendCommand("INCR", "counter");
            assertEquals(2L, client.readReply().longValue());
        }
    }

    @Test
    void appendPreservesValueAndTtl() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            client.sendCommand("APPEND", "word", "Hello");
            assertEquals(5L, client.readReply().longValue());
            client.sendCommand("APPEND", "word", "World");
            assertEquals(10L, client.readReply().longValue());
            client.sendCommand("GET", "word");
            assertEquals("HelloWorld", RespTestClient.bulkAsText(client.readReply()));

            client.sendCommand("EXPIRE", "word", "100");
            assertEquals(1L, client.readReply().longValue());
            client.sendCommand("APPEND", "word", "!");
            assertEquals(11L, client.readReply().longValue());
            client.sendCommand("TTL", "word");
            long ttl = client.readReply().longValue();
            assertTrue(ttl > 0L && ttl <= 100L);
        }
    }

    @Test
    void setClearsPreviousExpiry() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            client.sendCommand("SET", "temp", "v", "PX", "5000");
            assertEquals("OK", client.readReply().stringValue());
            client.sendCommand("SET", "temp", "v2");
            assertEquals("OK", client.readReply().stringValue());
            client.sendCommand("TTL", "temp");
            assertEquals(-1L, client.readReply().longValue());
        }
    }

    @Test
    void expireTtlPersistLifecycle() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            client.sendCommand("TTL", "ghost");
            assertEquals(-2L, client.readReply().longValue());

            client.sendCommand("SET", "k", "v");
            client.readReply();
            client.sendCommand("TTL", "k");
            assertEquals(-1L, client.readReply().longValue());

            client.sendCommand("EXPIRE", "k", "100");
            assertEquals(1L, client.readReply().longValue());
            client.sendCommand("TTL", "k");
            assertTrue(client.readReply().longValue() > 0L);

            client.sendCommand("PERSIST", "k");
            assertEquals(1L, client.readReply().longValue());
            client.sendCommand("TTL", "k");
            assertEquals(-1L, client.readReply().longValue());
        }
    }

    @Test
    void mgetAndMset() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            String[] sameSlotKeys = sameSlotKeys(gateway, 3);
            client.sendCommand("MSET", sameSlotKeys[0], "1", sameSlotKeys[1], "2");
            assertEquals("OK", client.readReply().stringValue());

            client.sendCommand("MGET", sameSlotKeys[0], sameSlotKeys[2], sameSlotKeys[1]);
            RespData reply = client.readReply();
            assertEquals(RespType.ARRAY, reply.type());
            List<RespData> items = reply.array();
            assertEquals(3, items.size());
            assertEquals("1", RespTestClient.bulkAsText(items.get(0)));
            assertTrue(items.get(1).isNull());
            assertEquals("2", RespTestClient.bulkAsText(items.get(2)));
        }
    }

    static String[] sameSlotKeys(TestGateway gateway, int count) {
        java.util.Map<Integer, java.util.List<String>> byShard = new java.util.HashMap<>();
        int target = -1;
        for (int index = 0; index < 100_000; index++) {
            String key = "key" + index;
            int shard = gateway.service().registry().router().shardIndex(key.getBytes());
            java.util.List<String> group = byShard.computeIfAbsent(shard, ignored -> new java.util.ArrayList<>());
            group.add(key);
            if (group.size() == count) {
                target = shard;
                break;
            }
        }
        return byShard.get(target).toArray(String[]::new);
    }

    @Test
    void unknownCommandReturnsDeterministicError() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            client.sendCommand("FROBNICATE", "x");
            RespData reply = client.readReply();
            assertEquals(RespType.ERROR, reply.type());
            assertTrue(reply.stringValue().startsWith("ERR unknown command"));
        }
    }

    @Test
    void pingIsSupported() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            client.sendCommand("PING");
            assertEquals("PONG", client.readReply().stringValue());
        }
    }
}
