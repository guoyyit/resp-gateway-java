package com.github.guoyyit.respgatewayjava.tests;

import com.github.guoyyit.respgatewayjava.config.GatewayConfig;
import com.github.guoyyit.respgatewayjava.resp.RespData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionLifecycleTest {

    @Test
    void idleConnectionIsReclaimed() throws Exception {
        GatewayConfig config = TestGateway.defaultConfig();
        config.idleTimeout = Duration.ofSeconds(1);
        try (TestGateway gateway = TestGateway.start(config);
             RespTestClient client = new RespTestClient(gateway.port(), 5000)) {
            client.sendCommand("PING");
            assertEquals("PONG", client.readReply().stringValue());

            long deadline = System.currentTimeMillis() + 5000L;
            boolean closed = false;
            while (System.currentTimeMillis() < deadline) {
                int read = client.rawSocket().getInputStream().read();
                if (read == -1) {
                    closed = true;
                    break;
                }
            }
            assertTrue(closed, "idle connection should have been closed");
        }
    }

    @Test
    void halfOpenIncompleteFrameIsCleanedUp() throws Exception {
        GatewayConfig config = TestGateway.defaultConfig();
        config.halfOpenTimeout = Duration.ofSeconds(1);
        try (TestGateway gateway = TestGateway.start(config);
             RespTestClient client = new RespTestClient(gateway.port(), 5000)) {
            client.sendRaw("$5\r\nab".getBytes(StandardCharsets.UTF_8));

            long deadline = System.currentTimeMillis() + 5000L;
            boolean closed = false;
            while (System.currentTimeMillis() < deadline) {
                int read = client.rawSocket().getInputStream().read();
                if (read == -1) {
                    closed = true;
                    break;
                }
            }
            assertTrue(closed, "half-open connection should have been cleaned up");
        }
    }

    @Test
    void stickyAndFragmentedBytesOverTcpWorkEndToEnd() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             Socket socket = new Socket("127.0.0.1", gateway.port())) {
            socket.setTcpNoDelay(true);
            byte[] merged = ("*1\r\n$4\r\nPING\r\n*2\r\n$3\r\nGET\r\n$1\r\nz\r\n")
                    .getBytes(StandardCharsets.UTF_8);

            socket.getOutputStream().write(merged, 0, 7);
            Thread.sleep(30L);
            socket.getOutputStream().write(merged, 7, 3);
            Thread.sleep(30L);
            socket.getOutputStream().write(merged, 10, merged.length - 10);
            socket.getOutputStream().flush();

            java.io.InputStream input = socket.getInputStream();
            assertEquals("+PONG\r\n", readLineReply(input));
            assertEquals("$-1\r\n", readLineReply(input));
        }
    }

    private static String readLineReply(java.io.InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        int current;
        while ((current = input.read()) != -1) {
            builder.append((char) current);
            if (builder.length() >= 2
                    && builder.charAt(builder.length() - 2) == '\r'
                    && builder.charAt(builder.length() - 1) == '\n') {
                return builder.toString();
            }
        }
        throw new IOException("unexpected end of stream");
    }

    @Test
    void protocolErrorDoesNotBreakFollowingCommands() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            client.sendRaw("?garbage\r\n".getBytes(StandardCharsets.UTF_8));
            RespData error = client.readReply();
            assertEquals(com.github.guoyyit.respgatewayjava.resp.RespType.ERROR, error.type());

            client.sendCommand("PING");
            assertEquals("PONG", client.readReply().stringValue());
        }
    }

    @Test
    void overlongFrameOverTcpReturnsErrorAndKeepsConnectionUsable() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            StringBuilder overlong = new StringBuilder();
            overlong.append("$1024\r\n");
            for (int index = 0; index < 1024; index++) {
                overlong.append('x');
            }
            overlong.append("\r\n");
            client.sendRaw(overlong.toString().getBytes(StandardCharsets.UTF_8));

            RespData error = client.readReply();
            assertEquals(com.github.guoyyit.respgatewayjava.resp.RespType.ERROR, error.type());
            assertTrue(error.stringValue().contains("exceeds"));

            client.sendCommand("PING");
            assertEquals("PONG", client.readReply().stringValue());
        }
    }
}
