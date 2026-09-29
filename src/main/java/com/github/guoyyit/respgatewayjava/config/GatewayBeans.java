package com.github.guoyyit.respgatewayjava.config;

import com.github.guoyyit.respgatewayjava.admin.GatewayAdmin;
import com.github.guoyyit.respgatewayjava.command.CommandDispatcher;
import com.github.guoyyit.respgatewayjava.health.HealthChecker;
import com.github.guoyyit.respgatewayjava.persist.PersistenceService;
import com.github.guoyyit.respgatewayjava.server.GatewayServer;
import com.github.guoyyit.respgatewayjava.shard.ShardManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayBeans {

    @Bean
    public PersistenceService persistenceService(GatewayProperties properties) {
        return new PersistenceService(properties);
    }

    @Bean
    public ShardManager shardManager(GatewayProperties properties, PersistenceService persistenceService) {
        int persistedCount = persistenceService.loadShardCount();
        int shardCount = persistedCount > 0 ? persistedCount : properties.getShardCount();
        long epoch = persistedCount > 0 ? persistenceService.loadEpoch() : 1L;
        ShardManager manager = new ShardManager(shardCount,
                properties.getShardQueueCapacity(), epoch);
        if (persistedCount > 0) {
            persistenceService.loadData(manager);
        }
        return manager;
    }

    @Bean
    public CommandDispatcher commandDispatcher(ShardManager shardManager) {
        return new CommandDispatcher(shardManager);
    }

    @Bean
    public HealthChecker healthChecker(GatewayProperties properties, ShardManager shardManager) {
        return new HealthChecker(shardManager,
                properties.getHealthCheckIntervalMs(),
                properties.getHealthCheckTimeoutMs());
    }

    @Bean
    public GatewayAdmin gatewayAdmin(ShardManager shardManager, PersistenceService persistenceService) {
        return new GatewayAdmin(shardManager, persistenceService);
    }

    @Bean
    public GatewayServer gatewayServer(GatewayProperties properties,
                                       ShardManager shardManager,
                                       CommandDispatcher dispatcher) {
        return new GatewayServer(properties, shardManager, dispatcher);
    }

    @Bean
    public GatewayLifecycle gatewayLifecycle(GatewayServer server,
                                             HealthChecker healthChecker,
                                             ShardManager shardManager,
                                             GatewayAdmin gatewayAdmin) {
        return new GatewayLifecycle(server, healthChecker, shardManager, gatewayAdmin);
    }
}
