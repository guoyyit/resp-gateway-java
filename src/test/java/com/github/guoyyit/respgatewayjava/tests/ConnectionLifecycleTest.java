package com.github.guoyyit.respgatewayjava.tests;

import com.github.guoyyit.respgatewayjava.tests.support.GatewayTestHarness;
import com.github.guoyyit.respgatewayjava.tests.support.RespTestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void idleConnectionIsReclaimed() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir, 4, 1000, 400);
             RespTestClient client = new RespTestClient(h.port(), 3000)) {
            client.sendCommand("PING");
            assertEquals("PONG", client.readReply());
            long deadline = System.currentTimeMillis() + 3000;
            boolean closed = false;
            while (System.currentTimeMillis() < deadline) {
                if (client.isClosedByPeer()) {
                    closed = true;
                    break;
                }
                Thread.sleep(50);
            }
            assertTrue(closed, "idle connection should have been closed by the gateway");
        }
    }

    @Test
    void activeConnectionIsNotClosedByIdleSweep() throws Exception {
        try (GatewayTestHarness h = GatewayTestHarness.start(tempDir, 4, 1000, 400);
             RespTestClient client = new RespTestClient(h.port(), 3000)) {
            for (int i = 0; i < 4; i++) {
                Thread.sleep(200);
                client.sendCommand("PING");
                assertEquals("PONG", client.readReply());
            }
            client.sendCommand("PING");
            assertEquals("PONG", client.readReply());
        }
    }

    @Test
    void stoppingGatewayClosesClientConnectionsExplicitly() throws Exception {
        GatewayTestHarness h = GatewayTestHarness.start(tempDir);
        RespTestClient client = new RespTestClient(h.port(), 3000);
        client.sendCommand("PING");
        assertEquals("PONG", client.readReply());
        h.close();
        long deadline = System.currentTimeMillis() + 3000;
        boolean closed = false;
        while (System.currentTimeMillis() < deadline) {
            if (client.isClosedByPeer()) {
                closed = true;
                break;
            }
            Thread.sleep(20);
        }
        client.close();
        assertTrue(closed, "client connection must be closed explicitly when the gateway stops");
    }
}
