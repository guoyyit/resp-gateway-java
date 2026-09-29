package com.github.guoyyit.respgatewayjava.config;

import com.github.guoyyit.respgatewayjava.admin.GatewayAdmin;
import com.github.guoyyit.respgatewayjava.health.HealthChecker;
import com.github.guoyyit.respgatewayjava.server.GatewayServer;
import com.github.guoyyit.respgatewayjava.shard.ShardManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class GatewayLifecycle {

    private final GatewayServer server;
    private final HealthChecker healthChecker;
    private final ShardManager shardManager;
    private final GatewayAdmin gatewayAdmin;
    private final Thread shutdownHook;
    private final CountDownLatch keepAliveLatch = new CountDownLatch(1);
    private ExecutorService keepAlive;

    public GatewayLifecycle(GatewayServer server,
                            HealthChecker healthChecker,
                            ShardManager shardManager,
                            GatewayAdmin gatewayAdmin) {
        this.server = server;
        this.healthChecker = healthChecker;
        this.shardManager = shardManager;
        this.gatewayAdmin = gatewayAdmin;
        this.shutdownHook = new Thread(this::gracefulShutdown, "resp-shutdown");
    }

    @PostConstruct
    public void start() {
        server.start();
        healthChecker.start();
        keepAlive = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "resp-keepalive");
            thread.setDaemon(false);
            return thread;
        });
        keepAlive.submit(() -> {
            try {
                keepAliveLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
        }
    }

    @PreDestroy
    public void stop() {
        gracefulShutdown();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
        }
    }

    private void gracefulShutdown() {
        keepAliveLatch.countDown();
        try {
            gatewayAdmin.save();
        } finally {
            server.stop();
            healthChecker.stop();
            shardManager.shutdown();
            if (keepAlive != null) {
                keepAlive.shutdownNow();
                try {
                    keepAlive.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
