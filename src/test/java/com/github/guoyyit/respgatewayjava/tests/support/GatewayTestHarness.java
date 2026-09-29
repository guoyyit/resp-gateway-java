package com.github.guoyyit.respgatewayjava.tests.support;

import com.github.guoyyit.respgatewayjava.admin.GatewayAdmin;
import com.github.guoyyit.respgatewayjava.command.CommandDispatcher;
import com.github.guoyyit.respgatewayjava.config.GatewayProperties;
import com.github.guoyyit.respgatewayjava.health.HealthChecker;
import com.github.guoyyit.respgatewayjava.persist.PersistenceService;
import com.github.guoyyit.respgatewayjava.server.GatewayServer;
import com.github.guoyyit.respgatewayjava.shard.ShardManager;

import java.nio.file.Path;

public class GatewayTestHarness implements AutoCloseable {

    private final GatewayProperties properties;
    private final ShardManager shardManager;
    private final GatewayServer server;
    private final HealthChecker healthChecker;
    private final PersistenceService persistenceService;
    private final GatewayAdmin admin;

    private GatewayTestHarness(GatewayProperties properties,
                               ShardManager shardManager,
                               GatewayServer server,
                               HealthChecker healthChecker,
                               PersistenceService persistenceService,
                               GatewayAdmin admin) {
        this.properties = properties;
        this.shardManager = shardManager;
        this.server = server;
        this.healthChecker = healthChecker;
        this.persistenceService = persistenceService;
        this.admin = admin;
    }

    public static GatewayTestHarness start(Path dataDir) {
        return start(dataDir, 4, 1024, 1000, 60_000);
    }

    public static GatewayTestHarness start(Path dataDir, int shardCount) {
        return start(dataDir, shardCount, 1024, 1000, 60_000);
    }

    public static GatewayTestHarness start(Path dataDir, int shardCount,
                                           int healthIntervalMs, long idleTimeoutMs) {
        return start(dataDir, shardCount, 1024, healthIntervalMs, idleTimeoutMs);
    }

    public static GatewayTestHarness start(Path dataDir, int shardCount, int queueCapacity,
                                           int healthIntervalMs, long idleTimeoutMs) {
        GatewayProperties properties = new GatewayProperties();
        properties.setPort(0);
        properties.setShardCount(shardCount);
        properties.setShardQueueCapacity(queueCapacity);
        properties.setHealthCheckIntervalMs(healthIntervalMs);
        properties.setHealthCheckTimeoutMs(100);
        properties.setIdleTimeoutMs(idleTimeoutMs);
        properties.setDataDir(dataDir.toString());

        PersistenceService persistenceService = new PersistenceService(properties);
        int persistedCount = persistenceService.loadShardCount();
        int effectiveCount = persistedCount > 0 ? persistedCount : shardCount;
        long epoch = persistedCount > 0 ? persistenceService.loadEpoch() : 1L;
        ShardManager manager = new ShardManager(effectiveCount, queueCapacity, epoch);
        if (persistedCount > 0) {
            persistenceService.loadData(manager);
        }
        CommandDispatcher dispatcher = new CommandDispatcher(manager);
        GatewayServer server = new GatewayServer(properties, manager, dispatcher);
        HealthChecker healthChecker = new HealthChecker(manager,
                properties.getHealthCheckIntervalMs(), properties.getHealthCheckTimeoutMs());
        GatewayAdmin admin = new GatewayAdmin(manager, persistenceService);
        server.start();
        healthChecker.start();
        return new GatewayTestHarness(properties, manager, server, healthChecker,
                persistenceService, admin);
    }

    public int port() {
        return server.getBoundPort();
    }

    public ShardManager shardManager() {
        return shardManager;
    }

    public GatewayAdmin admin() {
        return admin;
    }

    public GatewayProperties properties() {
        return properties;
    }

    public PersistenceService persistence() {
        return persistenceService;
    }

    public void save() {
        persistenceService.save(shardManager);
    }

    @Override
    public void close() {
        server.stop();
        healthChecker.stop();
        shardManager.shutdown();
    }
}
