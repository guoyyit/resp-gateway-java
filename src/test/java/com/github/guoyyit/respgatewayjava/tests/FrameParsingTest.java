package com.github.guoyyit.respgatewayjava.tests;

import com.github.guoyyit.respgatewayjava.tests.support.GatewayTestHarness;
import com.github.guoyyit.respgatewayjava.tests.support.RespTestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameParsingTest {

    @TempDir
    Path tempDir;

    @Test
    void allReplyTypesRoundTrip() throws Exception {
        try (GatewayTestHarness harness = GatewayTestHarness.start(tempDir);
             RespTestClient client = new RespTestClient(harness.port())) {
            client.sendCommand("PING");
            assertEquals("PONG", client.readReply());

            client.sendCommand("SET", "greeting", "hello");
            assertEquals("OK", client.readReply());

            client.sendCommand("GET", "greeting");
            assertEquals("hello", client.readReply());

            client.sendCommand("GET", "missing");
            assertNull(client.readReply());

            client.sendCommand("INCR", "counter");
            assertEquals(1L, client.readReply());

            client.sendCommand("GET", "counter");
            assertEquals("1", client.readReply());
        }
    }

    @Test
    void stickyPacketsAreParsedAsTwoCommands() throws Exception {
        try (GatewayTestHarness harness = GatewayTestHarness.start(tempDir);
             RespTestClient client = new RespTestClient(harness.port())) {
            byte[] first = RespTestClient.encode("SET", "sticky", "a");
            byte[] second = RespTestClient.encode("GET", "sticky");
            byte[] joined = new byte[first.length + second.length];
            System.arraycopy(first, 0, joined, 0, first.length);
            System.arraycopy(second, 0, joined, first.length, second.length);
            client.sendRaw(joined);
            assertEquals("OK", client.readReply());
            assertEquals("a", client.readReply());
        }
    }

    @Test
    void halfPacketsAreParsedAfterMoreBytesArrive() throws Exception {
        try (GatewayTestHarness harness = GatewayTestHarness.start(tempDir);
             RespTestClient client = new RespTestClient(harness.port())) {
            byte[] frame = RespTestClient.encode("SET", "half", "world");
            client.sendRaw(new byte[]{frame[0], frame[1], frame[2]});
            client.sendRaw(java.util.Arrays.copyOfRange(frame, 3, frame.length));
            assertEquals("OK", client.readReply());
            client.sendCommand("GET", "half");
            assertEquals("world", client.readReply());
        }
    }

    @Test
    void invalidFrameReturnsErrorAndConnectionSurvives() throws Exception {
        try (GatewayTestHarness harness = GatewayTestHarness.start(tempDir);
             RespTestClient client = new RespTestClient(harness.port())) {
            client.sendRaw("+not-a-command-bulk\r\n");
            Object first = client.readReply();
            assertTrue(first instanceof RespTestClient.ErrorReply,
                    "expected protocol error, got " + first);

            client.sendCommand("SET", "after-error", "kept");
            assertEquals("OK", client.readReply());
            client.sendCommand("GET", "after-error");
            assertEquals("kept", client.readReply());
        }
    }

    @Test
    void oversizedBulkStringReturnsProtocolErrorWithoutBreakingLaterCommands() throws Exception {
        try (GatewayTestHarness harness = GatewayTestHarness.start(tempDir);
             RespTestClient client = new RespTestClient(harness.port())) {
            byte[] big = new byte[1024];
            java.util.Arrays.fill(big, (byte) 'x');
            byte[] frame = ("$" + big.length + "\r\n").getBytes(StandardCharsets.UTF_8);
            harness.properties().setMaxFrameBytes(128);
            client.sendRaw(frame);
            client.sendRaw(big);
            client.sendRaw("\r\n".getBytes(StandardCharsets.UTF_8));
            Object first = client.readReply();
            assertTrue(first instanceof RespTestClient.ErrorReply);

            client.sendCommand("PING");
            assertEquals("PONG", client.readReply());
        }
    }

    @Test
    void emptyStringValueIsDistinctFromMissingKey() throws Exception {
        try (GatewayTestHarness harness = GatewayTestHarness.start(tempDir);
             RespTestClient client = new RespTestClient(harness.port())) {
            client.sendCommand("SET", "empty", "");
            assertEquals("OK", client.readReply());
            client.sendCommand("GET", "empty");
            assertEquals("", client.readReply());
            client.sendCommand("GET", "missing");
            assertNull(client.readReply());
            client.sendCommand("EXISTS", "empty");
            assertEquals(1L, client.readReply());
        }
    }

    @Test
    void mgetPreservesNullAndValueOrder() throws Exception {
        try (GatewayTestHarness harness = GatewayTestHarness.start(tempDir);
             RespTestClient client = new RespTestClient(harness.port())) {
            client.sendCommand("SET", "{tag}a", "1");
            assertEquals("OK", client.readReply());
            client.sendCommand("SET", "{tag}c", "3");
            assertEquals("OK", client.readReply());
            client.sendCommand("MGET", "{tag}a", "{tag}b", "{tag}c");
            assertEquals(new java.util.ArrayList<>(java.util.Arrays.asList("1", null, "3")),
                    client.readReply());
        }
    }

    @Test
    void binarySafeBulkPayloadRoundTrips() throws Exception {
        try (GatewayTestHarness harness = GatewayTestHarness.start(tempDir);
             RespTestClient client = new RespTestClient(harness.port())) {
            byte[] payload = new byte[]{0, 1, 2, '\r', '\n', -1, 127};
            StringBuilder frame = new StringBuilder();
            frame.append("*3\r\n$3\r\nSET\r\n$3\r\nbin\r\n$")
                    .append(payload.length).append("\r\n");
            byte[] head = frame.toString().getBytes(StandardCharsets.UTF_8);
            byte[] tail = "\r\n".getBytes(StandardCharsets.UTF_8);
            byte[] request = new byte[head.length + payload.length + tail.length];
            System.arraycopy(head, 0, request, 0, head.length);
            System.arraycopy(payload, 0, request, head.length, payload.length);
            System.arraycopy(tail, 0, request, head.length + payload.length, tail.length);
            client.sendRaw(request);
            assertEquals("OK", client.readReply());
            client.sendCommand("GET", "bin");
            assertArrayEquals(payload, client.readBulkBytes());
        }
    }
}
