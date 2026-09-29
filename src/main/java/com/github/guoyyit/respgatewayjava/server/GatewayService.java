package com.github.guoyyit.respgatewayjava.server;

import com.github.guoyyit.respgatewayjava.cluster.HealthMonitor;
import com.github.guoyyit.respgatewayjava.cluster.InProcessShard;
import com.github.guoyyit.respgatewayjava.cluster.Shard;
import com.github.guoyyit.respgatewayjava.cluster.ShardRegistry;
import com.github.guoyyit.respgatewayjava.config.GatewayConfig;
import com.github.guoyyit.respgatewayjava.config.PersistenceStore;
import com.github.guoyyit.respgatewayjava.routing.Topology;

import java.time.Duration;

public final class GatewayService {

    private final GatewayConfig config;
    private final PersistenceStore persistenceStore;
    private ShardRegistry registry;
    private GatewayServer server;
    private HealthMonitor healthMonitor;

    public GatewayService(GatewayConfig config) {
        this.config = config;
        this.persistenceStore = new PersistenceStore(config.dataDir);
    }

    public synchronized void start() {
        if (server != null) {
            return;
        }
        GatewayConfig persistedConfig = persistenceStore.loadConfig();
        int shardCount = config.shardCount;
        String policy = config.topologyChangePolicy;
        long epoch = 1L;
        PersistenceStore.PersistedTopology persistedTopology = persistenceStore.loadTopology();
        if (persistedTopology != null) {
            shardCount = persistedTopology.shardCount;
            epoch = Math.max(1L, persistedTopology.epoch);
            if (persistedTopology.policy != null) {
                policy = persistedTopology.policy;
            }
        }
        registry = new ShardRegistry(shardCount, config.shardQueueCapacity, epoch);
        registry.setPolicy(policy);

        server = new GatewayServer(config, registry);
        server.start();
        healthMonitor = new HealthMonitor(registry, config.healthCheckInterval,
                Duration.ofMillis(500L));
        healthMonitor.start();
        persistenceStore.save(registry.topology(), config, policy);
    }

    public synchronized void stop() {
        if (healthMonitor != null) {
            healthMonitor.stop();
        }
        if (server != null) {
            server.stop();
        }
        if (registry != null) {
            registry.shutdown();
        }
        server = null;
        registry = null;
        healthMonitor = null;
    }

    public int port() {
        return server == null ? -1 : server.boundPort();
    }

    public Topology topology() {
        return registry.topology();
    }

    public ShardRegistry registry() {
        return registry;
    }

    public synchronized void changeTopology(int newShardCount, String policy) {
        registry.applyTopology(newShardCount, policy);
        registry.setPolicy(policy);
        persistenceStore.save(registry.topology(), config, policy);
    }

    public void forceShardDown(int shardIndex) {
        shard(shardIndex).setUnavailable(true);
    }

    public void restoreShard(int shardIndex) {
        shard(shardIndex).setUnavailable(false);
        shard(shardIndex).setHealthDown(false);
    }

    public void setShardSlow(int shardIndex, boolean slow) {
        shard(shardIndex).setSlow(slow);
    }

    public InProcessShard shard(int shardIndex) {
        Shard shard = registry.shard(shardIndex);
        if (!(shard instanceof InProcessShard inProcessShard)) {
            throw new IllegalStateException("local in-process shard expected");
        }
        return inProcessShard;
    }

    public GatewayConfig config() {
        return config;
    }
}
