package com.github.guoyyit.respgatewayjava.cluster;

import com.github.guoyyit.respgatewayjava.command.Command;
import com.github.guoyyit.respgatewayjava.command.CommandHandler;
import com.github.guoyyit.respgatewayjava.command.GatewayError;
import com.github.guoyyit.respgatewayjava.resp.RespData;
import com.github.guoyyit.respgatewayjava.storage.ShardStore;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InProcessShard implements Shard {

    private final int index;
    private final ShardStore store;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean forcedDown = new AtomicBoolean(false);
    private final AtomicBoolean healthDown = new AtomicBoolean(false);
    private final AtomicBoolean slow = new AtomicBoolean(false);
    private volatile long slowDelayMillis = 200L;

    public InProcessShard(int index, int queueCapacity) {
        this.index = index;
        this.store = new ShardStore(index);
        LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueCapacity);
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, queue,
                runnable -> {
                    Thread thread = new Thread(runnable, "shard-" + index);
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public int index() {
        return index;
    }

    @Override
    public boolean healthy() {
        return !forcedDown.get() && !healthDown.get();
    }

    @Override
    public boolean isForcedDown() {
        return forcedDown.get();
    }

    @Override
    public void setHealthDown(boolean down) {
        healthDown.set(down);
    }

    @Override
    public void setSlow(boolean slow) {
        this.slow.set(slow);
    }

    public void setSlowDelayMillis(long delayMillis) {
        this.slowDelayMillis = delayMillis;
    }

    @Override
    public void setUnavailable(boolean unavailable) {
        forcedDown.set(unavailable);
        if (unavailable) {
            healthDown.set(false);
        }
    }

    @Override
    public CompletableFuture<Void> probe() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (forcedDown.get()) {
            result.completeExceptionally(new TimeoutException("shard forced down"));
            return result;
        }
        if (slow.get()) {
            result.completeExceptionally(new TimeoutException("shard is too slow"));
            return result;
        }
        try {
            executor.execute(() -> result.complete(null));
        } catch (RejectedExecutionException exception) {
            result.completeExceptionally(new TimeoutException("shard queue is saturated"));
        }
        return result;
    }

    @Override
    public CompletableFuture<RespData> submit(Command command, long topologyEpoch,
                                              java.util.function.LongSupplier currentEpochSupplier,
                                              java.util.function.Supplier<String> policySupplier) {
        CompletableFuture<RespData> reply = new CompletableFuture<>();
        if (forcedDown.get() || healthDown.get()) {
            reply.completeExceptionally(GatewayError.clusterDown(
                    "shard " + index + " is unavailable"));
            return reply;
        }
        try {
            executor.execute(() -> runCommand(command, reply, topologyEpoch,
                    currentEpochSupplier, policySupplier));
        } catch (RejectedExecutionException exception) {
            reply.completeExceptionally(GatewayError.busy(
                    "shard " + index + " is saturated, backpressure engaged"));
        }
        return reply;
    }

    private void runCommand(Command command, CompletableFuture<RespData> reply,
                            long commandEpoch,
                            java.util.function.LongSupplier currentEpochSupplier,
                            java.util.function.Supplier<String> policySupplier) {
        try {
            if (slow.get()) {
                Thread.sleep(slowDelayMillis);
            }
            if ("REJECT".equals(policySupplier.get())
                    && commandEpoch != currentEpochSupplier.getAsLong()) {
                reply.completeExceptionally(GatewayError.clusterDown(
                        "topology changed while command was in flight"));
                return;
            }
            if (forcedDown.get() || healthDown.get()) {
                reply.completeExceptionally(GatewayError.clusterDown(
                        "shard " + index + " is unavailable"));
                return;
            }
            reply.complete(CommandHandler.execute(command, store));
        } catch (GatewayError exception) {
            reply.completeExceptionally(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            reply.completeExceptionally(new GatewayError("ERR shard execution interrupted"));
        } catch (RuntimeException exception) {
            reply.completeExceptionally(new GatewayError(
                    "ERR internal shard failure: " + exception.getMessage()));
        }
    }

    @Override
    public int pendingCount() {
        return executor.getQueue().size() + executor.getActiveCount();
    }

    public ShardStore storeForTest() {
        return store;
    }

    public void shutdown() {
        executor.shutdownNow();
        store.shutdown();
    }
}
