package com.github.guoyyit.respgatewayjava.server;

import com.github.guoyyit.respgatewayjava.cluster.ShardRegistry;
import com.github.guoyyit.respgatewayjava.config.GatewayConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

public final class GatewayServer {

    private final GatewayConfig config;
    private final ShardRegistry registry;

    private ServerSocket serverSocket;
    private ExecutorService acceptors;
    private ExecutorService connectionWorkers;
    private final Set<ClientConnection> activeConnections = ConcurrentHashMap.newKeySet();
    private volatile boolean running;

    public GatewayServer(GatewayConfig config, ShardRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    public void start() {
        if (running) {
            return;
        }
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("127.0.0.1", config.port));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        running = true;
        acceptors = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gateway-acceptor");
            thread.setDaemon(true);
            return thread;
        });
        connectionWorkers = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "gateway-conn");
            thread.setDaemon(true);
            return thread;
        });
        acceptors.execute(this::acceptLoop);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                ClientConnection connection = new ClientConnection(socket, registry, config);
                activeConnections.add(connection);
                connectionWorkers.execute(() -> {
                    try {
                        connection.run();
                    } finally {
                        activeConnections.remove(connection);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException exception) {
                return;
            } catch (IOException exception) {
                if (running) {
                    continue;
                }
                return;
            }
        }
    }

    public void stop() {
        running = false;
        for (ClientConnection connection : activeConnections) {
            connection.requestShutdown();
        }
        activeConnections.clear();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // 关停时忽略关闭异常。
            }
        }
        if (acceptors != null) {
            acceptors.shutdownNow();
        }
        if (connectionWorkers != null) {
            connectionWorkers.shutdownNow();
        }
    }

    public int boundPort() {
        if (serverSocket == null || !serverSocket.isBound()) {
            return -1;
        }
        return serverSocket.getLocalPort();
    }
}
