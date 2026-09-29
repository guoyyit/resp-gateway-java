package com.github.guoyyit.respgatewayjava.cluster;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class HealthMonitor {

    private final ShardRegistry registry;
    private final Duration interval;
    private final Duration probeTimeout;
    private ScheduledExecutorService scheduler;

    public HealthMonitor(ShardRegistry registry, Duration interval) {
        this(registry, interval, Duration.ofMillis(500L));
    }

    public HealthMonitor(ShardRegistry registry, Duration interval, Duration probeTimeout) {
        this.registry = registry;
        this.interval = interval;
        this.probeTimeout = probeTimeout;
    }

    public void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shard-health-monitor");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::checkAllShards,
                interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void checkAllShards() {
        for (Shard shard : registry.shards()) {
            checkOne(shard);
        }
    }

    private void checkOne(Shard shard) {
        if (shard.isForcedDown()) {
            return;
        }
        try {
            shard.probe().get(probeTimeout.toMillis(), TimeUnit.MILLISECONDS);
            shard.setHealthDown(false);
        } catch (Exception exception) {
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            if (cause instanceof TimeoutException) {
                shard.setHealthDown(true);
            } else {
                shard.setHealthDown(true);
            }
        }
    }
}
