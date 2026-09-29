package com.github.guoyyit.respgatewayjava.server;

import com.github.guoyyit.respgatewayjava.command.Command;
import com.github.guoyyit.respgatewayjava.command.CommandDispatcher;
import com.github.guoyyit.respgatewayjava.config.GatewayProperties;
import com.github.guoyyit.respgatewayjava.resp.RespData;
import com.github.guoyyit.respgatewayjava.resp.RespParser;
import com.github.guoyyit.respgatewayjava.resp.RespType;
import com.github.guoyyit.respgatewayjava.shard.ShardManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class GatewayServer {

    private final GatewayProperties properties;
    private final ShardManager shardManager;
    private final CommandDispatcher dispatcher;

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private Thread reactorThread;
    private volatile boolean running;
    private final Map<SocketChannel, ClientState> states = new ConcurrentHashMap<>();
    private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(64 * 1024);

    public GatewayServer(GatewayProperties properties,
                         ShardManager shardManager,
                         CommandDispatcher dispatcher) {
        this.properties = properties;
        this.shardManager = shardManager;
        this.dispatcher = dispatcher;
    }

    public void start() {
        try {
            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            serverChannel.bind(new InetSocketAddress("127.0.0.1", properties.getPort()));
            serverChannel.configureBlocking(false);
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException e) {
            throw new IllegalStateException("failed to start gateway server", e);
        }
        running = true;
        reactorThread = new Thread(this::eventLoop, "resp-reactor");
        reactorThread.setDaemon(true);
        reactorThread.start();
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        selector.wakeup();
        try {
            reactorThread.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (ClientState state : new ArrayList<>(states.values())) {
            try {
                state.connection.close();
            } catch (IOException ignored) {
            }
        }
        states.clear();
        try {
            serverChannel.close();
            selector.close();
        } catch (IOException ignored) {
        }
    }

    public int getBoundPort() {
        try {
            return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        } catch (IOException e) {
            return -1;
        }
    }

    private void eventLoop() {
        while (running) {
            try {
                selector.select(250);
                if (!running) {
                    break;
                }
                reconcileInterests();
                flushPendingWrites();
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        accept();
                } else {
                    ClientState state = (ClientState) key.attachment();
                    if (key.isReadable()) {
                        read(key, state);
                    }
                    if (key.isValid() && key.isWritable()) {
                        write(key, state);
                    }
                }
                }
                sweepIdle();
            } catch (Throwable t) {
                if (running) {
                    t.printStackTrace();
                }
            }
        }
    }

    private void accept() throws IOException {
        SocketChannel channel;
        while ((channel = serverChannel.accept()) != null) {
            channel.configureBlocking(false);
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            RespParser parser = new RespParser(properties.getMaxFrameBytes());
            ClientConnection connection =
                    new ClientConnection(channel, parser, properties.getClientWriteBufferBytes());
            ClientState state = new ClientState(connection);
            channel.register(selector, SelectionKey.OP_READ, state);
            states.put(channel, state);
        }
    }

    private void read(SelectionKey key, ClientState state) {
        ClientConnection connection = state.connection;
        SocketChannel channel = connection.getChannel();
        try {
            if (state.readSuspended) {
                if (connection.flush()) {
                    state.readSuspended = false;
                }
                applyInterests(key, state);
                return;
            }
            int totalRead = 0;
            boolean peerClosed = false;
            while (totalRead < properties.getClientReadBufferBytes()) {
                readBuffer.clear();
                int n = channel.read(readBuffer);
                if (n == -1) {
                    peerClosed = true;
                    break;
                }
                if (n == 0) {
                    break;
                }
                totalRead += n;
                connection.touch();
                readBuffer.flip();
                connection.getParser().feed(readBuffer);
            }
            if (peerClosed) {
                closeState(state);
                return;
            }
            if (totalRead > 0) {
                processFrames(key, state);
            }
        } catch (IOException e) {
            closeState(state);
        }
    }

    private void processFrames(SelectionKey key, ClientState state) {
        ClientConnection connection = state.connection;
        RespParser parser = connection.getParser();
        boolean protocolError = parser.hasProtocolError();
        if (protocolError) {
            sendError(state, "ERR", "protocol error: invalid RESP frame");
        }
        List<RespData> frames = parser.drainCompletes();
        for (RespData frame : frames) {
            if (frame.getType() != RespType.ARRAY || frame.isNull()) {
                sendError(state, "ERR", "expected a non-null RESP array command");
                continue;
            }
            CommandResult result = toCommand(frame);
            if (result.error != null) {
                enqueueReply(state, result.error);
            } else {
                dispatchCommand(state, result.command);
            }
        }
        applyInterests(key, state);
    }

    private void reconcileInterests() {
        for (SelectionKey key : selector.keys()) {
            if (key.attachment() instanceof ClientState state) {
                applyInterests(key, state);
            }
        }
    }

    private void flushPendingWrites() {
        for (SelectionKey key : selector.keys()) {
            if (key.attachment() instanceof ClientState state
                    && key.isValid()
                    && (key.interestOps() & SelectionKey.OP_WRITE) != 0) {
                write(key, state);
            }
        }
    }

    private record CommandResult(Command command, RespData error) {
    }

    private CommandResult toCommand(RespData frame) {
        List<RespData> elements = frame.getArray();
        if (elements.isEmpty()) {
            return new CommandResult(null,
                    RespData.error("ERR", "wrong number of arguments: empty command"));
        }
        List<byte[]> args = new ArrayList<>(elements.size() - 1);
        String name = null;
        for (int i = 0; i < elements.size(); i++) {
            RespData element = elements.get(i);
            if (element.getType() != RespType.BULK_STRING || element.isNull()) {
                return new CommandResult(null,
                        RespData.error("ERR", "command elements must be non-null bulk strings"));
            }
            if (i == 0) {
                name = new String(element.getBytes(), StandardCharsets.UTF_8);
            } else {
                args.add(element.getBytes());
            }
        }
        return new CommandResult(new Command(name, args), null);
    }

    private void dispatchCommand(ClientState state, Command command) {
        ReplyOrderer orderer = state.orderer;
        long seq = orderer.nextSeq();
        long epoch = shardManager.getEpoch();
        dispatcher.dispatchAsync(command, epoch).whenComplete((reply, throwable) -> {
            RespData finalReply = throwable == null ? reply
                    : RespData.error("ERR", "internal error: " + throwable.getMessage());
            orderer.complete(seq, finalReply);
            selector.wakeup();
        });
    }

    private void write(SelectionKey key, ClientState state) {
        ClientConnection connection = state.connection;
        try {
            boolean drained = connection.flush();
            if (drained) {
                state.writeInterest = false;
                if (state.readSuspended) {
                    state.readSuspended = false;
                }
                applyInterests(key, state);
            }
        } catch (IOException e) {
            closeState(state);
        }
    }

    private void sendError(ClientState state, String prefix, String message) {
        enqueueReply(state, RespData.error(prefix, message));
    }

    private void enqueueReply(ClientState state, RespData reply) {
        ClientConnection connection = state.connection;
        boolean accepted = connection.enqueue(reply.encode());
        if (accepted) {
            state.writeInterest = true;
            return;
        }
        state.readSuspended = true;
    }

    private void applyInterests(SelectionKey key, ClientState state) {
        if (key == null || !key.isValid()) {
            return;
        }
        int ops = 0;
        ops |= SelectionKey.OP_READ;
        if (state.writeInterest) {
            ops |= SelectionKey.OP_WRITE;
        }
        key.interestOps(ops);
    }

    private void sweepIdle() {
        long now = System.nanoTime();
        long idleNanos = TimeUnit.MILLISECONDS.toNanos(properties.getIdleTimeoutMs());
        for (ClientState state : states.values()) {
            if (now - state.connection.getLastActivityNanos() > idleNanos) {
                closeState(state);
            }
        }
    }

    private void closeState(ClientState state) {
        try {
            state.connection.close();
        } catch (IOException ignored) {
        }
        states.remove(state.connection.getChannel());
    }

    private static final class ClientState {
        private final ClientConnection connection;
        private final ReplyOrderer orderer;
        private volatile boolean writeInterest;
        private volatile boolean readSuspended;

        private ClientState(ClientConnection connection) {
            this.connection = connection;
            this.orderer = new ReplyOrderer(reply -> {
                connection.enqueue(reply.encode());
                writeInterest = true;
            });
        }
    }
}
