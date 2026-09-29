package com.github.guoyyit.respgatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private int port = 6379;
    private int shardCount = 4;
    private int shardQueueCapacity = 1024;
    private int clientReadBufferBytes = 1 << 20;
    private int clientWriteBufferBytes = 1 << 20;
    private long idleTimeoutMs = 60_000;
    private int maxFrameBytes = 64 * 1024 * 1024;
    private long healthCheckIntervalMs = 2_000;
    private long healthCheckTimeoutMs = 500;
    private String dataDir = "data";

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public int getShardCount() { return shardCount; }
    public void setShardCount(int shardCount) { this.shardCount = shardCount; }
    public int getShardQueueCapacity() { return shardQueueCapacity; }
    public void setShardQueueCapacity(int v) { this.shardQueueCapacity = v; }
    public int getClientReadBufferBytes() { return clientReadBufferBytes; }
    public void setClientReadBufferBytes(int v) { this.clientReadBufferBytes = v; }
    public int getClientWriteBufferBytes() { return clientWriteBufferBytes; }
    public void setClientWriteBufferBytes(int v) { this.clientWriteBufferBytes = v; }
    public long getIdleTimeoutMs() { return idleTimeoutMs; }
    public void setIdleTimeoutMs(long v) { this.idleTimeoutMs = v; }
    public int getMaxFrameBytes() { return maxFrameBytes; }
    public void setMaxFrameBytes(int v) { this.maxFrameBytes = v; }
    public long getHealthCheckIntervalMs() { return healthCheckIntervalMs; }
    public void setHealthCheckIntervalMs(long v) { this.healthCheckIntervalMs = v; }
    public long getHealthCheckTimeoutMs() { return healthCheckTimeoutMs; }
    public void setHealthCheckTimeoutMs(long v) { this.healthCheckTimeoutMs = v; }
    public String getDataDir() { return dataDir; }
    public void setDataDir(String v) { this.dataDir = v; }
}
