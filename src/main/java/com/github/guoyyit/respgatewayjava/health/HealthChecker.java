package com.github.guoyyit.respgatewayjava.health;

import com.github.guoyyit.respgatewayjava.shard.Shard;
import com.github.guoyyit.respgatewayjava.shard.ShardManager;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class HealthChecker {

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final ShardManager shardManager;
    private final long intervalMs;
    private final long timeoutMs;
    private ScheduledExecutorService scheduler;

    public HealthChecker(ShardManager shardManager, long intervalMs, long timeoutMs) {
        this.shardManager = shardManager;
        this.intervalMs = intervalMs;
        this.timeoutMs = timeoutMs;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "resp-health-" + THREAD_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::checkAll, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void checkAll() {
        List<Shard> snapshot = shardManager.getShards();
        for (Shard shard : snapshot) {
            shard.setHealthy(probe(shard));
        }
    }

    private boolean probe(Shard shard) {
        return !shard.isFault();
    }
}
