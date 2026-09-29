package com.github.guoyyit.respgatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "resp.gateway")
public class GatewayProperties {

    private int port = 6390;
    private int shardCount = 4;
    private int shardQueueCapacity = 256;
    private long maxFrameBytes = 64L * 1024 * 1024;
    private int connectionReadBufferBytes = 1024 * 1024;
    private int maxInflightPerConnection = 256;
    private Duration idleTimeout = Duration.ofMinutes(5);
    private Duration halfOpenTimeout = Duration.ofMinutes(2);
    private Duration healthCheckInterval = Duration.ofSeconds(2);
    private String topologyChangePolicy = "REJECT";
    private String dataDir = "data";

    public GatewayConfig toConfig() {
        GatewayConfig config = new GatewayConfig();
        config.port = port;
        config.shardCount = shardCount;
        config.shardQueueCapacity = shardQueueCapacity;
        config.maxFrameBytes = maxFrameBytes;
        config.connectionReadBufferBytes = connectionReadBufferBytes;
        config.maxInflightPerConnection = maxInflightPerConnection;
        config.idleTimeout = idleTimeout;
        config.halfOpenTimeout = halfOpenTimeout;
        config.healthCheckInterval = healthCheckInterval;
        config.topologyChangePolicy = topologyChangePolicy;
        config.dataDir = dataDir;
        return config;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getShardCount() {
        return shardCount;
    }

    public void setShardCount(int shardCount) {
        this.shardCount = shardCount;
    }

    public int getShardQueueCapacity() {
        return shardQueueCapacity;
    }

    public void setShardQueueCapacity(int shardQueueCapacity) {
        this.shardQueueCapacity = shardQueueCapacity;
    }

    public long getMaxFrameBytes() {
        return maxFrameBytes;
    }

    public void setMaxFrameBytes(long maxFrameBytes) {
        this.maxFrameBytes = maxFrameBytes;
    }

    public int getConnectionReadBufferBytes() {
        return connectionReadBufferBytes;
    }

    public void setConnectionReadBufferBytes(int connectionReadBufferBytes) {
        this.connectionReadBufferBytes = connectionReadBufferBytes;
    }

    public int getMaxInflightPerConnection() {
        return maxInflightPerConnection;
    }

    public void setMaxInflightPerConnection(int maxInflightPerConnection) {
        this.maxInflightPerConnection = maxInflightPerConnection;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Duration getHalfOpenTimeout() {
        return halfOpenTimeout;
    }

    public void setHalfOpenTimeout(Duration halfOpenTimeout) {
        this.halfOpenTimeout = halfOpenTimeout;
    }

    public Duration getHealthCheckInterval() {
        return healthCheckInterval;
    }

    public void setHealthCheckInterval(Duration healthCheckInterval) {
        this.healthCheckInterval = healthCheckInterval;
    }

    public String getTopologyChangePolicy() {
        return topologyChangePolicy;
    }

    public void setTopologyChangePolicy(String topologyChangePolicy) {
        this.topologyChangePolicy = topologyChangePolicy;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }
}
