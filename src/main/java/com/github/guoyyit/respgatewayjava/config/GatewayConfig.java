package com.github.guoyyit.respgatewayjava.config;

import java.time.Duration;

public final class GatewayConfig {

    public int port = 6390;
    public int shardCount = 4;
    public int shardQueueCapacity = 256;
    public long maxFrameBytes = 64L * 1024 * 1024;
    public int connectionReadBufferBytes = 1024 * 1024;
    public int maxInflightPerConnection = 256;
    public Duration idleTimeout = Duration.ofMinutes(5);
    public Duration halfOpenTimeout = Duration.ofMinutes(2);
    public Duration healthCheckInterval = Duration.ofSeconds(2);
    public String topologyChangePolicy = "REJECT";
    public String dataDir = "data";

    public static GatewayConfig defaults() {
        return new GatewayConfig();
    }
}
