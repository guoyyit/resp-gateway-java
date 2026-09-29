package com.github.guoyyit.respgatewayjava.cluster;

import com.github.guoyyit.respgatewayjava.command.Command;
import com.github.guoyyit.respgatewayjava.command.GatewayError;
import com.github.guoyyit.respgatewayjava.resp.RespData;
import com.github.guoyyit.respgatewayjava.routing.KeyRouter;
import com.github.guoyyit.respgatewayjava.routing.Topology;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class ShardRegistry {

    private final int queueCapacity;
    private final List<Shard> shards = new CopyOnWriteArrayList<>();
    private final AtomicLong topologyEpoch = new AtomicLong(1L);
    private volatile Topology topology;
    private volatile KeyRouter router;
    private volatile String policy = "REJECT";

    public ShardRegistry(int shardCount, int queueCapacity) {
        this(shardCount, queueCapacity, 1L);
    }

    public ShardRegistry(int shardCount, int queueCapacity, long initialEpoch) {
        this.queueCapacity = queueCapacity;
        this.topologyEpoch.set(initialEpoch);
        this.topology = new Topology(shardCount, initialEpoch);
        this.router = new KeyRouter(topology);
        for (int index = 0; index < shardCount; index++) {
            shards.add(new InProcessShard(index, queueCapacity));
        }
    }

    public Topology topology() {
        return topology;
    }

    public KeyRouter router() {
        return router;
    }

    public String policy() {
        return policy;
    }

    public CompletableFuture<RespData> route(Command command, List<byte[]> keys,
                                              long commandEpoch) {
        if (keys.isEmpty()) {
            return shards.get(0).submit(command, commandEpoch,
                    () -> topology.epoch(), () -> policy);
        }
        int target = router.shardIndex(keys.get(0));
        for (int index = 1; index < keys.size(); index++) {
            int candidate = router.shardIndex(keys.get(index));
            if (candidate != target) {
                CompletableFuture<RespData> rejected = new CompletableFuture<>();
                rejected.completeExceptionally(GatewayError.crossSlot(
                        "keys in request hash to different slots"));
                return rejected;
            }
        }
        if (target >= shards.size()) {
            CompletableFuture<RespData> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(GatewayError.clusterDown(
                    "shard " + target + " is not active"));
            return rejected;
        }
        return shards.get(target).submit(command, commandEpoch,
                () -> topology.epoch(), () -> policy);
    }

    public Shard shard(int index) {
        return shards.get(index);
    }

    public List<Shard> shards() {
        return List.copyOf(shards);
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public synchronized void applyTopology(int newShardCount, String requestedPolicy) {
        if (newShardCount == topology.shardCount()) {
            return;
        }
        policy = requestedPolicy;
        if ("DRAIN".equals(requestedPolicy)) {
            if (newShardCount < topology.shardCount()) {
                drainShards(newShardCount);
            }
        }
        while (shards.size() < newShardCount) {
            int nextIndex = shards.size();
            shards.add(new InProcessShard(nextIndex, queueCapacity));
        }
        while (shards.size() > newShardCount) {
            Shard removed = shards.remove(shards.size() - 1);
            if (removed instanceof InProcessShard inProcessShard) {
                inProcessShard.shutdown();
            }
        }
        long epoch = topologyEpoch.incrementAndGet();
        topology = new Topology(newShardCount, epoch);
        router = new KeyRouter(topology);
    }

    private void drainShards(int targetCount) {
        List<Shard> draining = new ArrayList<>();
        for (int index = targetCount; index < shards.size(); index++) {
            draining.add(shards.get(index));
        }
        long deadline = System.currentTimeMillis() + 5000L;
        for (Shard shard : draining) {
            while (shard.pendingCount() > 0 && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public void shutdown() {
        for (Shard shard : shards) {
            if (shard instanceof InProcessShard inProcessShard) {
                inProcessShard.shutdown();
            }
        }
    }
}
