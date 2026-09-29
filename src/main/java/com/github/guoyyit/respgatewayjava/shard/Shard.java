package com.github.guoyyit.respgatewayjava.shard;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class Shard {

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final int id;
    private final ShardStore store;
    private final ShardExecutor executor;
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final AtomicBoolean fault = new AtomicBoolean(false);
    private volatile long slowMillis;

    public Shard(int id, int queueCapacity) {
        this.id = id;
        this.store = new ShardStore();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable,
                    "resp-shard-" + id + "-" + THREAD_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ShardExecutor(queueCapacity, factory);
    }

    public int getId() { return id; }

    public ShardStore getStore() { return store; }

    public boolean isHealthy() {
        return healthy.get();
    }

    public void setHealthy(boolean value) {
        healthy.set(value);
    }

    public void setFault(boolean value) {
        fault.set(value);
    }

    public boolean isFault() {
        return fault.get();
    }

    public void setSlowMillis(long millis) {
        this.slowMillis = millis;
    }

    public long getSlowMillis() {
        return slowMillis;
    }

    public int queueSize() {
        return executor.queue.size();
    }

    public void drainQueuedTasksForTopologyChange() {
        executor.drainQueuedTasks();
    }

    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        if (!healthy.get()) {
            return CompletableFuture.failedFuture(new ShardUnavailableException(id));
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    if (executor.isShutdown()) {
                        future.completeExceptionally(new ShardTopologyChangedException(id));
                        return;
                    }
                    long delay = slowMillis;
                    if (delay > 0) {
                        Thread.sleep(delay);
                    }
                    future.complete(task.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException rejected) {
            return CompletableFuture.failedFuture(new ShardBusyException(id));
        }
        return future;
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private static final class ShardExecutor extends AbstractExecutorService {

        private final LinkedBlockingQueue<Runnable> queue;
        private final ThreadFactory threadFactory;
        private volatile boolean shutdown;
        private Thread worker;

        private ShardExecutor(int queueCapacity, ThreadFactory threadFactory) {
            this.queue = new LinkedBlockingQueue<>(queueCapacity);
            this.threadFactory = threadFactory;
            this.worker = threadFactory.newThread(this::runLoop);
            this.worker.start();
        }

        private void runLoop() {
            while (true) {
                Runnable runnable;
                try {
                    runnable = queue.take();
                } catch (InterruptedException e) {
                    if (shutdown) {
                        drainAndFail();
                        return;
                    }
                    continue;
                }
                runnable.run();
            }
        }

        private void drainAndFail() {
            drainQueuedTasks();
        }

        private void drainQueuedTasks() {
            Runnable runnable;
            while ((runnable = queue.poll()) != null) {
                try {
                    runnable.run();
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new RejectedExecutionException("shard executor shut down");
            }
            if (!queue.offer(command)) {
                throw new RejectedExecutionException("shard queue is full");
            }
        }

        @Override
        public void shutdown() {
            shutdown = true;
            if (worker != null) {
                worker.interrupt();
            }
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            shutdown = true;
            java.util.List<Runnable> remaining = new java.util.ArrayList<>();
            queue.drainTo(remaining);
            if (worker != null) {
                worker.interrupt();
            }
            return remaining;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && queue.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (!isTerminated()) {
                if (System.nanoTime() >= deadline) {
                    return false;
                }
                Thread.sleep(5);
            }
            return true;
        }
    }

    public static final class ShardUnavailableException extends RuntimeException {
        public ShardUnavailableException(int id) {
            super("shard " + id + " unavailable");
        }
    }

    public static final class ShardBusyException extends RuntimeException {
        public ShardBusyException(int id) {
            super("shard " + id + " busy");
        }
    }

    public static final class ShardTopologyChangedException extends RuntimeException {
        public ShardTopologyChangedException(int id) {
            super("shard " + id + " topology changed");
        }
    }
}
