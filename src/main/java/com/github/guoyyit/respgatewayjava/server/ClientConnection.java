package com.github.guoyyit.respgatewayjava.server;

import com.github.guoyyit.respgatewayjava.resp.RespParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

public class ClientConnection {

    private static final AtomicLong ID_SEQ = new AtomicLong();

    private final long id = ID_SEQ.incrementAndGet();
    private final SocketChannel channel;
    private final RespParser parser;
    private final int writeBufferBytes;
    private final Queue<ByteBuffer> outputQueue = new ArrayDeque<>();
    private int queuedBytes;
    private volatile long lastActivityNanos = System.nanoTime();
    private volatile boolean closed;

    public ClientConnection(SocketChannel channel, RespParser parser, int writeBufferBytes) {
        this.channel = channel;
        this.parser = parser;
        this.writeBufferBytes = writeBufferBytes;
    }

    public SocketChannel getChannel() { return channel; }

    public RespParser getParser() { return parser; }

    public long getId() { return id; }

    public long getLastActivityNanos() { return lastActivityNanos; }

    public void touch() {
        lastActivityNanos = System.nanoTime();
    }

    public boolean isClosed() {
        return closed;
    }

    public synchronized boolean enqueue(ByteBuffer buffer) {
        if (closed) {
            return false;
        }
        if (queuedBytes + buffer.remaining() > writeBufferBytes) {
            return false;
        }
        outputQueue.add(buffer);
        queuedBytes += buffer.remaining();
        return true;
    }

    public synchronized boolean flush() throws IOException {
        while (!outputQueue.isEmpty()) {
            ByteBuffer head = outputQueue.peek();
            int size = head.remaining();
            channel.write(head);
            if (head.hasRemaining()) {
                queuedBytes -= size - head.remaining();
                return false;
            }
            queuedBytes -= size;
            outputQueue.poll();
        }
        return true;
    }

    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        outputQueue.clear();
        queuedBytes = 0;
        try {
            channel.close();
        } finally {
            notifyAll();
        }
    }
}
