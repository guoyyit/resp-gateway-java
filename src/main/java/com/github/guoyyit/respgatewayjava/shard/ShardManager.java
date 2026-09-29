package com.github.guoyyit.respgatewayjava.shard;

import com.github.guoyyit.respgatewayjava.cluster.ShardRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class ShardManager {

    private volatile List<Shard> shards;
    private volatile int shardCount;
    private final int queueCapacity;
    private final AtomicLong epoch = new AtomicLong(1);

    public ShardManager(int shardCount, int queueCapacity) {
        this(shardCount, queueCapacity, 1L);
    }

    public ShardManager(int shardCount, int queueCapacity, long initialEpoch) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        this.shardCount = shardCount;
        this.queueCapacity = queueCapacity;
        this.shards = createShards(shardCount);
        this.epoch.set(initialEpoch);
    }

    private List<Shard> createShards(int count) {
        List<Shard> created = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            created.add(new Shard(i, queueCapacity));
        }
        return List.copyOf(created);
    }

    public int getShardCount() {
        return shardCount;
    }

    public List<Shard> getShards() {
        return shards;
    }

    public Shard shardForKey(String key) {
        List<Shard> snapshot = shards;
        int index = ShardRouter.shardIndexForKey(key, snapshot.size());
        return snapshot.get(index);
    }

    public int shardIndexForKey(String key) {
        return ShardRouter.shardIndexForKey(key, shardCount);
    }

    public long getEpoch() {
        return epoch.get();
    }

    public long bumpEpoch() {
        return epoch.incrementAndGet();
    }

    public synchronized void replaceShards(int newCount) {
        List<Shard> previous = this.shards;
        this.shards = createShards(newCount);
        this.shardCount = newCount;
        epoch.incrementAndGet();
        for (Shard shard : previous) {
            retire(shard);
        }
    }

    public synchronized void restoreTopology(int newCount, long newEpoch) {
        List<Shard> previous = this.shards;
        this.shards = createShards(newCount);
        this.shardCount = newCount;
        this.epoch.set(newEpoch);
        for (Shard shard : previous) {
            retire(shard);
        }
    }

    private void retire(Shard shard) {
        Thread drainer = new Thread(() -> {
            shard.drainQueuedTasksForTopologyChange();
            shard.shutdown();
        }, "resp-shard-retire-" + shard.getId());
        drainer.setDaemon(true);
        drainer.start();
    }

    public void shutdown() {
        for (Shard shard : shards) {
            shard.shutdown();
        }
    }
}
