package com.github.guoyyit.respgatewayjava.config;

import com.github.guoyyit.respgatewayjava.server.GatewayService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayLifecycle implements SmartLifecycle {

    private final GatewayProperties properties;
    private GatewayService gatewayService;
    private boolean running;

    public GatewayLifecycle(GatewayProperties properties) {
        this.properties = properties;
    }

    public GatewayService gatewayService() {
        return gatewayService;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        gatewayService = new GatewayService(properties.toConfig());
        gatewayService.start();
        running = true;
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        gatewayService.stop();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
