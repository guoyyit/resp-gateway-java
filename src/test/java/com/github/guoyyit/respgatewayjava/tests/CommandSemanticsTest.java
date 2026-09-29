package com.github.guoyyit.respgatewayjava.tests;

import com.github.guoyyit.respgatewayjava.tests.support.GatewayTestHarness;
import com.github.guoyyit.respgatewayjava.tests.support.RespTestClient;
import com.github.guoyyit.respgatewayjava.tests.support.RespTestClient.ErrorReply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandSemanticsTest {

    @TempDir
    Path tempDir;

    @Test
    void setOverwriteClearsExpiry() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir);
             RespTestClient c = new RespTestClient(h.port())) {
            c.sendCommand("SET", "k", "v1", "PX", "100000");
            assertEquals("OK", c.readReply());
            c.sendCommand("TTL", "k");
            assertTrue((Long) c.readReply() > 0);

            c.sendCommand("SET", "k", "v2");
            assertEquals("OK", c.readReply());
            c.sendCommand("TTL", "k");
            assertEquals(-1L, c.readReply());
            c.sendCommand("GET", "k");
            assertEquals("v2", c.readReply());
        }
    }

    @Test
    void incrOnNonIntegerReturnsTypeErrorAndKeepsValue() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir);
             RespTestClient c = new RespTestClient(h.port())) {
            c.sendCommand("SET", "text", "not-a-number");
            assertEquals("OK", c.readReply());
            c.sendCommand("INCR", "text");
            Object reply = c.readReply();
            assertTrue(reply instanceof ErrorReply, "expected error, got " + reply);
            c.sendCommand("GET", "text");
            assertEquals("not-a-number", c.readReply());

            c.sendCommand("SET", "zero", "");
            assertEquals("OK", c.readReply());
            c.sendCommand("INCR", "zero");
            assertTrue(c.readReply() instanceof ErrorReply);
        }
    }

    @Test
    void incrPreservesRemainingTtl() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir);
             RespTestClient c = new RespTestClient(h.port())) {
            c.sendCommand("SET", "counter", "10", "PX", "100000");
            assertEquals("OK", c.readReply());
            c.sendCommand("INCR", "counter");
            assertEquals(11L, c.readReply());
            c.sendCommand("TTL", "counter");
            long ttl = (Long) c.readReply();
            assertTrue(ttl > 0 && ttl <= 100, "ttl should be retained, was " + ttl);
            c.sendCommand("APPEND", "counter", "0");
            assertEquals(3L, c.readReply());
            c.sendCommand("TTL", "counter");
            assertTrue((Long) c.readReply() > 0);
        }
    }

    @Test
    void appendCreatesValueAndAppends() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir);
             RespTestClient c = new RespTestClient(h.port())) {
            c.sendCommand("APPEND", "a", "Hello");
            assertEquals(5L, c.readReply());
            c.sendCommand("APPEND", "a", " World");
            assertEquals(11L, c.readReply());
            c.sendCommand("GET", "a");
            assertEquals("Hello World", c.readReply());
        }
    }

    @Test
    void delExistsPersistWorkAsExpected() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir);
             RespTestClient c = new RespTestClient(h.port())) {
            c.sendCommand("SET", "x", "1");
            assertEquals("OK", c.readReply());
            c.sendCommand("SET", "y", "2");
            assertEquals("OK", c.readReply());
            c.sendCommand("EXISTS", "x");
            assertEquals(1L, c.readReply());
            c.sendCommand("DEL", "x", "y", "z");
            assertEquals(2L, c.readReply());
            c.sendCommand("EXISTS", "x");
            assertEquals(0L, c.readReply());
            c.sendCommand("PERSIST", "x");
            assertEquals(0L, c.readReply());
        }
    }

    @Test
    void expireTtlAndPersistRoundTrip() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir);
             RespTestClient c = new RespTestClient(h.port())) {
            c.sendCommand("SET", "k", "v");
            assertEquals("OK", c.readReply());
            c.sendCommand("EXPIRE", "k", "100");
            assertEquals(1L, c.readReply());
            c.sendCommand("TTL", "k");
            long ttl = (Long) c.readReply();
            assertTrue(ttl > 0 && ttl <= 100);
            c.sendCommand("PERSIST", "k");
            assertEquals(1L, c.readReply());
            c.sendCommand("TTL", "k");
            assertEquals(-1L, c.readReply());

            c.sendCommand("EXPIRE", "missing", "10");
            assertEquals(0L, c.readReply());
            c.sendCommand("TTL", "missing");
            assertEquals(-2L, c.readReply());
        }
    }

    @Test
    void expiredKeyIsInvisibleEvenWithoutAccess() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir);
             RespTestClient c = new RespTestClient(h.port())) {
            c.sendCommand("SET", "fast", "x", "PX", "40");
            assertEquals("OK", c.readReply());
            Thread.sleep(70);
            c.sendCommand("GET", "fast");
            assertNull(c.readReply());
            c.sendCommand("EXISTS", "fast");
            assertEquals(0L, c.readReply());
            c.sendCommand("TTL", "fast");
            assertEquals(-2L, c.readReply());
        }
    }

    @Test
    void msetAndMgetWithinSameShardSucceed() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir);
             RespTestClient c = new RespTestClient(h.port())) {
            c.sendCommand("MSET", "{u}1", "a", "{u}2", "b");
            assertEquals("OK", c.readReply());
            c.sendCommand("MGET", "{u}1", "{u}2");
            assertEquals(java.util.Arrays.asList("a", "b"), c.readReply());
        }
    }

    @Test
    void crossSlotMultiKeyReturnsDeterministicError() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir, 4);
             RespTestClient c = new RespTestClient(h.port())) {
            String firstKey = findKeyForShard(h, 0);
            String otherKey = findKeyForShard(h, 1);
            c.sendCommand("MGET", firstKey, otherKey);
            Object reply = c.readReply();
            assertTrue(reply instanceof ErrorReply error && error.message().startsWith("CROSSSLOT"),
                    "expected CROSSSLOT, got " + reply);
            c.sendCommand("PING");
            assertEquals("PONG", c.readReply());
        }
    }

    @Test
    void unknownCommandReturnsDeterministicError() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir);
             RespTestClient c = new RespTestClient(h.port())) {
            c.sendCommand("FLUSHALL");
            Object reply = c.readReply();
            assertTrue(reply instanceof ErrorReply error && error.message().startsWith("ERR unknown command"),
                    "got " + reply);
            c.sendCommand("FOOBAR", "x");
            assertTrue(c.readReply() instanceof ErrorReply);
        }
    }

    @Test
    void wrongArityReturnsError() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir);
             RespTestClient c = new RespTestClient(h.port())) {
            c.sendCommand("GET");
            assertTrue(c.readReply() instanceof ErrorReply);
            c.sendCommand("SET", "only-key");
            assertTrue(c.readReply() instanceof ErrorReply);
            c.sendCommand("MSET", "k1");
            assertTrue(c.readReply() instanceof ErrorReply);
            c.sendCommand("PING");
            assertEquals("PONG", c.readReply());
        }
    }

    private static String findKeyForShard(GatewayTestHarness h, int shardIndex) {
        for (int i = 0; i < 100_000; i++) {
            String key = "key-" + i;
            if (h.shardManager().shardIndexForKey(key) == shardIndex) {
                return key;
            }
        }
        throw new IllegalStateException("no key found for shard " + shardIndex);
    }
}
