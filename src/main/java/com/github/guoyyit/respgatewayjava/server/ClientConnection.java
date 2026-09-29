package com.github.guoyyit.respgatewayjava.server;

import com.github.guoyyit.respgatewayjava.cluster.ShardRegistry;
import com.github.guoyyit.respgatewayjava.command.Command;
import com.github.guoyyit.respgatewayjava.command.CommandHandler;
import com.github.guoyyit.respgatewayjava.command.CommandName;
import com.github.guoyyit.respgatewayjava.command.GatewayError;
import com.github.guoyyit.respgatewayjava.config.GatewayConfig;
import com.github.guoyyit.respgatewayjava.resp.RespData;
import com.github.guoyyit.respgatewayjava.resp.RespParser;
import com.github.guoyyit.respgatewayjava.resp.RespWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

final class ClientConnection implements Runnable {

    private final Socket socket;
    private final ShardRegistry registry;
    private final GatewayConfig config;

    private final Object orderLock = new Object();
    private final TreeMap<Long, byte[]> readyReplies = new TreeMap<>();
    private final java.util.concurrent.atomic.AtomicInteger inflight =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private long nextSequence;
    private long nextWriteSequence;

    private volatile boolean closed;
    private Thread writerThread;
    private long lastReadMillis = System.currentTimeMillis();

    ClientConnection(Socket socket, ShardRegistry registry, GatewayConfig config) {
        this.socket = socket;
        this.registry = registry;
        this.config = config;
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(1000);

            writerThread = new Thread(this::writeLoop, "conn-writer");
            writerThread.setDaemon(true);
            writerThread.start();
            readLoop();
        } catch (IOException exception) {
            closeQuietly();
        } finally {
            finishConnection();
        }
    }

    private void readLoop() throws IOException {
        RespParser parser = new RespParser(config.maxFrameBytes);
        ByteBuffer readBuffer = ByteBuffer.allocate(config.connectionReadBufferBytes);
        long halfOpenStart = -1L;

        while (!closed) {
            readBuffer.clear();
            int read;
            try {
                read = socket.getInputStream().read(readBuffer.array());
            } catch (SocketTimeoutException timeout) {
                long idleMillis = System.currentTimeMillis() - lastReadMillis;
                if (idleMillis > config.idleTimeout.toMillis()) {
                    return;
                }
                if (halfOpenStart > 0
                        && System.currentTimeMillis() - halfOpenStart
                        > config.halfOpenTimeout.toMillis()) {
                    return;
                }
                continue;
            }
            if (read == -1) {
                return;
            }
            lastReadMillis = System.currentTimeMillis();
            halfOpenStart = -1L;
            readBuffer.position(0);
            readBuffer.limit(read);
            parser.append(readBuffer);

            while (true) {
                RespParser.FrameResult frame = parser.nextFrame();
                if (frame.kind() == RespParser.Kind.INCOMPLETE) {
                    if (parser.bufferedBytes() > 0) {
                        halfOpenStart = lastReadMillis;
                    }
                    break;
                }
                halfOpenStart = -1L;
                if (frame.kind() == RespParser.Kind.PROTOCOL_ERROR) {
                    dispatchReady(RespData.error(frame.errorMessage()));
                    continue;
                }
                halfOpenStart = -1L;
                dispatchCommand(frame.data());
            }
        }
    }

    private void dispatchCommand(RespData frame) {
        if (frame.type() != com.github.guoyyit.respgatewayjava.resp.RespType.ARRAY
                || frame.isNull() || frame.array().isEmpty()) {
            dispatchReady(RespData.error("ERR expected non-empty RESP array command"));
            return;
        }
        List<RespData> elements = frame.array();
        RespData nameElement = elements.get(0);
        if (nameElement.type() != com.github.guoyyit.respgatewayjava.resp.RespType.BULK_STRING
                || nameElement.isNull()) {
            dispatchReady(RespData.error("ERR command name must be a bulk string"));
            return;
        }
        String rawName = new String(nameElement.bulkBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        CommandName commandName = CommandName.fromString(rawName);
        List<RespData> arguments = new ArrayList<>(elements.size() - 1);
        for (int index = 1; index < elements.size(); index++) {
            RespData element = elements.get(index);
            if (element.type() != com.github.guoyyit.respgatewayjava.resp.RespType.BULK_STRING
                    || element.isNull()) {
                dispatchReady(RespData.error("ERR command arguments must be bulk strings"));
                return;
            }
            arguments.add(element);
        }
        Command command = new Command(commandName, arguments);

        if (commandName == CommandName.UNKNOWN) {
            dispatchReady(RespData.error(
                    "ERR unknown command '" + rawName + "'"));
            return;
        }

        List<byte[]> keys;
        try {
            keys = CommandHandler.keys(command);
        } catch (GatewayError exception) {
            dispatchReady(exception.toReply());
            return;
        }

        long epoch = registry.topology().epoch();
        CompletableFuture<RespData> future;
        try {
            future = registry.route(command, keys, epoch);
        } catch (GatewayError exception) {
            dispatchReady(exception.toReply());
            return;
        }
        long sequence = enqueueInflight();
        future.whenComplete((reply, error) -> {
            RespData response;
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                if (cause instanceof GatewayError gatewayError) {
                    response = gatewayError.toReply();
                } else {
                    response = RespData.error("ERR internal failure: " + cause.getMessage());
                }
            } else {
                response = reply;
            }
            publishReply(sequence, RespWriter.encode(response));
        });
    }

    private void dispatchReady(RespData reply) {
        long sequence = enqueueInflight();
        publishReply(sequence, RespWriter.encode(reply));
    }

    private long enqueueInflight() {
        while (inflight.get() >= config.maxInflightPerConnection && !closed) {
            synchronized (orderLock) {
                if (inflight.get() >= config.maxInflightPerConnection && !closed) {
                    try {
                        orderLock.wait(1000L);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        closeQuietly();
                    }
                }
            }
        }
        synchronized (orderLock) {
            inflight.incrementAndGet();
            return nextSequence++;
        }
    }

    private void publishReply(long sequence, byte[] payload) {
        synchronized (orderLock) {
            readyReplies.put(sequence, payload);
            orderLock.notifyAll();
        }
    }

    private void writeLoop() {
        OutputStream outputStream;
        try {
            outputStream = socket.getOutputStream();
        } catch (IOException exception) {
            closeQuietly();
            return;
        }
        while (!closed) {
            List<byte[]> batch = new ArrayList<>();
            synchronized (orderLock) {
                while (readyReplies.isEmpty() && !closed) {
                    try {
                        orderLock.wait(250L);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        closeQuietly();
                        return;
                    }
                }
                while (!readyReplies.isEmpty()
                        && readyReplies.firstKey() == nextWriteSequence) {
                    Map.Entry<Long, byte[]> head = readyReplies.pollFirstEntry();
                    batch.add(head.getValue());
                    nextWriteSequence++;
                }
            }
            try {
                for (byte[] payload : batch) {
                    outputStream.write(payload);
                }
                outputStream.flush();
            } catch (IOException exception) {
                closeQuietly();
                return;
            }
            if (!batch.isEmpty()) {
                synchronized (orderLock) {
                    inflight.addAndGet(-batch.size());
                    orderLock.notifyAll();
                }
            }
        }
    }

    private void finishConnection() {
        closeQuietly();
        synchronized (orderLock) {
            readyReplies.clear();
            inflight.set(0);
            orderLock.notifyAll();
        }
        if (writerThread != null) {
            writerThread.interrupt();
        }
    }

    private void closeQuietly() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭过程无需再传播错误。
        }
        synchronized (orderLock) {
            orderLock.notifyAll();
        }
    }

    void requestShutdown() {
        closeQuietly();
    }
}
