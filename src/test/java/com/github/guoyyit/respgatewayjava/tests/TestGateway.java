package com.github.guoyyit.respgatewayjava.tests;

import com.github.guoyyit.respgatewayjava.config.GatewayConfig;
import com.github.guoyyit.respgatewayjava.server.GatewayService;

import java.nio.file.Files;
import java.time.Duration;

final class TestGateway implements AutoCloseable {

    private final GatewayService service;

    private TestGateway(GatewayService service) {
        this.service = service;
    }

    static TestGateway start() throws Exception {
        return start(defaultConfig());
    }

    static TestGateway start(GatewayConfig config) {
        GatewayService service = new GatewayService(config);
        service.start();
        return new TestGateway(service);
    }

    static GatewayConfig defaultConfig() throws Exception {
        GatewayConfig config = new GatewayConfig();
        config.port = 0;
        config.shardCount = 4;
        config.shardQueueCapacity = 64;
        config.maxFrameBytes = 256;
        config.connectionReadBufferBytes = 64 * 1024;
        config.maxInflightPerConnection = 128;
        config.idleTimeout = Duration.ofSeconds(30);
        config.halfOpenTimeout = Duration.ofSeconds(30);
        config.healthCheckInterval = Duration.ofMillis(300);
        config.dataDir = Files.createTempDirectory("resp-gateway-test").toString();
        return config;
    }

    int port() {
        return service.port();
    }

    GatewayService service() {
        return service;
    }

    @Override
    public void close() {
        service.stop();
    }
}
