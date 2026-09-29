package com.github.guoyyit.respgatewayjava.tests;

import com.github.guoyyit.respgatewayjava.config.GatewayConfig;
import com.github.guoyyit.respgatewayjava.server.GatewayService;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartPersistenceTest {

    @Test
    void topologyAndConfigSurviveRestart() throws Exception {
        GatewayConfig config = TestGateway.defaultConfig();
        GatewayService first = new GatewayService(config);
        first.start();
        try {
            first.changeTopology(7, "REJECT");
            assertEquals(7, first.topology().shardCount());
        } finally {
            first.stop();
        }

        GatewayConfig restartedConfig = TestGateway.defaultConfig();
        restartedConfig.dataDir = config.dataDir;
        GatewayService restarted = new GatewayService(restartedConfig);
        restarted.start();
        try {
            assertEquals(7, restarted.topology().shardCount());
            assertTrue(restarted.topology().epoch() >= 2L);
        } finally {
            restarted.stop();
        }
    }

    @Test
    void stopClosesClientConnectionsExplicitly() throws Exception {
        GatewayConfig config = TestGateway.defaultConfig();
        GatewayService service = new GatewayService(config);
        service.start();
        RespTestClient client = new RespTestClient(service.port());
        try {
            client.sendCommand("PING");
            assertEquals("PONG", client.readReply().stringValue());

            service.stop();

            long deadline = System.currentTimeMillis() + 5000L;
            boolean closed = false;
            while (System.currentTimeMillis() < deadline) {
                int read = client.rawSocket().getInputStream().read();
                if (read == -1) {
                    closed = true;
                    break;
                }
            }
            assertTrue(closed, "client connection should be closed on gateway shutdown");
        } finally {
            client.close();
        }
    }

    @Test
    void uncompletedCommandsFailOnRestart() throws Exception {
        GatewayConfig config = TestGateway.defaultConfig();
        GatewayService service = new GatewayService(config);
        service.start();
        int port = service.port();
        service.stop();

        try (RespTestClient client = new RespTestClient(port)) {
            client.sendCommand("PING");
            client.readReply();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("closed")
                    || expected.getMessage().contains("refused"));
        }
    }
}
