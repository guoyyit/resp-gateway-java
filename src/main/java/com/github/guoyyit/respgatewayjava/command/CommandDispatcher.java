package com.github.guoyyit.respgatewayjava.command;

import com.github.guoyyit.respgatewayjava.cluster.ShardRouter;
import com.github.guoyyit.respgatewayjava.resp.RespData;
import com.github.guoyyit.respgatewayjava.shard.ShardManager;
import com.github.guoyyit.respgatewayjava.shard.ShardStore;
import com.github.guoyyit.respgatewayjava.shard.Entry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public class CommandDispatcher {

    private final ShardManager shardManager;
    private static final Pattern INTEGER_PATTERN = Pattern.compile("[+-]?\\d+");

    public CommandDispatcher(ShardManager shardManager) {
        this.shardManager = shardManager;
    }

    public RespData dispatch(Command command) {
        throw new UnsupportedOperationException("use dispatchAsync");
    }

    public CompletableFuture<RespData> dispatchAsync(Command command, long epoch) {
        String upper = command.getName().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "PING" -> completed(handlePing(command));
            case "GET" -> singleKey(command, 1, 1, (shard, key) -> {
                Entry entry = shard.getStore().get(key);
                return entry == null ? RespData.nullBulk() : RespData.bulkString(entry.getValue());
            }, epoch);
            case "SET" -> handleSet(command, epoch);
            case "DEL" -> multiKey(command, 1, (shard, keys) ->
                    RespData.integer(shard.getStore().deleteMany(keys)), epoch);
            case "EXISTS" -> multiKey(command, 1, (shard, keys) ->
                    RespData.integer(shard.getStore().countExisting(keys)), epoch);
            case "EXPIRE" -> singleKey(command, 2, 2, (shard, key) -> {
                long seconds = parseLong(command.getArgs().get(1));
                boolean ok = shard.getStore().expireAt(key,
                        System.nanoTime() + seconds * 1_000_000_000L);
                return RespData.integer(ok ? 1 : 0);
            }, epoch);
            case "TTL" -> singleKey(command, 1, 1, (shard, key) ->
                    ttlReply(shard.getStore(), key, false), epoch);
            case "PTTL" -> singleKey(command, 1, 1, (shard, key) ->
                    ttlReply(shard.getStore(), key, true), epoch);
            case "PERSIST" -> singleKey(command, 1, 1, (shard, key) ->
                    RespData.integer(shard.getStore().persist(key) ? 1 : 0), epoch);
            case "INCR" -> singleKey(command, 1, 1, (shard, key) ->
                    RespData.integer(increment(shard.getStore(), key, 1)), epoch);
            case "DECR" -> singleKey(command, 1, 1, (shard, key) ->
                    RespData.integer(increment(shard.getStore(), key, -1)), epoch);
            case "APPEND" -> singleKey(command, 2, 2, (shard, key) ->
                    RespData.integer(shard.getStore()
                            .append(key, command.getArgs().get(1), ShardStore.NO_EXPIRE)), epoch);
            case "MGET" -> multiKey(command, 1, (shard, keys) -> {
                List<RespData> values = new ArrayList<>(keys.size());
                for (String key : keys) {
                    Entry entry = shard.getStore().get(key);
                    values.add(entry == null ? RespData.nullBulk()
                            : RespData.bulkString(entry.getValue()));
                }
                return RespData.array(values);
            }, epoch);
            case "MSET" -> mset(command, epoch);
            default -> completed(RespData.error("ERR",
                    "unknown command '" + command.getName() + "'"));
        };
    }

    private RespData handlePing(Command command) {
        if (command.getArgs().isEmpty()) {
            return RespData.simpleString("PONG");
        }
        return RespData.bulkString(command.getArgs().get(0));
    }

    private CompletableFuture<RespData> handleSet(Command command, long epoch) {
        List<byte[]> args = command.getArgs();
        if (args.size() < 2) {
            return completed(RespData.error("ERR", "wrong number of arguments for 'set' command"));
        }
        String key = command.argString(0);
        byte[] value = args.get(1);
        long expireNanos = ShardStore.NO_EXPIRE;
        for (int i = 2; i < args.size(); i++) {
            String option = command.argString(i).toUpperCase(Locale.ROOT);
            switch (option) {
                case "EX" -> {
                    if (++i >= args.size()) {
                        return completed(RespData.error("ERR", "syntax error"));
                    }
                    long seconds = parseLong(args.get(i));
                    if (seconds <= 0) {
                        return completed(RespData.error("ERR", "invalid expire time in 'set' command"));
                    }
                    expireNanos = System.nanoTime() + seconds * 1_000_000_000L;
                }
                case "PX" -> {
                    if (++i >= args.size()) {
                        return completed(RespData.error("ERR", "syntax error"));
                    }
                    long millis = parseLong(args.get(i));
                    if (millis <= 0) {
                        return completed(RespData.error("ERR", "invalid expire time in 'set' command"));
                    }
                    expireNanos = System.nanoTime() + millis * 1_000_000L;
                }
                default -> {
                    return completed(RespData.error("ERR", "syntax error"));
                }
            }
        }
        final long finalExpireNanos = expireNanos;
        return singleKeyShard(key, epoch, shard -> {
            shard.getStore().put(key, value, finalExpireNanos);
            return RespData.simpleString("OK");
        });
    }

    private CompletableFuture<RespData> mset(Command command, long epoch) {
        List<byte[]> args = command.getArgs();
        if (args.isEmpty() || args.size() % 2 != 0) {
            return completed(RespData.error("ERR", "wrong number of arguments for 'mset' command"));
        }
        List<String> keys = new ArrayList<>(args.size() / 2);
        for (int i = 0; i < args.size(); i += 2) {
            keys.add(new String(args.get(i), StandardCharsets.UTF_8));
        }
        return requireSameShard(keys, epoch, (shard, sameKeys) -> {
            for (int i = 0; i < args.size(); i += 2) {
                shard.getStore().put(
                        new String(args.get(i), StandardCharsets.UTF_8),
                        args.get(i + 1),
                        ShardStore.NO_EXPIRE);
            }
            return RespData.simpleString("OK");
        });
    }

    private static long increment(ShardStore store, String key, long delta) {
        return store.increment(key, delta, ShardStore.NO_EXPIRE);
    }

    private static RespData ttlReply(ShardStore store, String key, boolean millis) {
        Long ttl = store.ttlMillis(key);
        if (ttl == -2L || ttl == -1L) {
            return RespData.integer(ttl);
        }
        if (millis) {
            return RespData.integer(ttl);
        }
        long seconds = ttl / 1000;
        if (ttl > 0 && seconds == 0) {
            seconds = 1;
        }
        return RespData.integer(seconds);
    }

    private long parseLong(byte[] value) {
        String text = new String(value, StandardCharsets.US_ASCII);
        if (!INTEGER_PATTERN.matcher(text).matches()) {
            throw new CommandException("ERR", "value is not an integer or out of range");
        }
        return Long.parseLong(text);
    }

    @FunctionalInterface
    private interface ShardAction {
        RespData apply(com.github.guoyyit.respgatewayjava.shard.Shard shard, String key);
    }

    @FunctionalInterface
    private interface MultiShardAction {
        RespData apply(com.github.guoyyit.respgatewayjava.shard.Shard shard, List<String> keys);
    }

    private CompletableFuture<RespData> singleKey(Command command, int minArgs, int maxArgs,
                                                  ShardAction action, long epoch) {
        List<byte[]> args = command.getArgs();
        if (args.size() < minArgs || args.size() > maxArgs) {
            return completed(RespData.error("ERR",
                    "wrong number of arguments for '" + command.getName().toLowerCase(Locale.ROOT) + "' command"));
        }
        return singleKeyShard(command.argString(0), epoch,
                shard -> action.apply(shard, command.argString(0)));
    }

    private CompletableFuture<RespData> multiKey(Command command, int minKeys,
                                                 MultiShardAction action, long epoch) {
        List<byte[]> args = command.getArgs();
        if (args.size() < minKeys) {
            return completed(RespData.error("ERR",
                    "wrong number of arguments for '" + command.getName().toLowerCase(Locale.ROOT) + "' command"));
        }
        List<String> keys = new ArrayList<>(args.size());
        for (byte[] arg : args) {
            keys.add(new String(arg, StandardCharsets.UTF_8));
        }
        return requireSameShard(keys, epoch, action);
    }

    private CompletableFuture<RespData> singleKeyShard(String key, long epoch,
                                                       java.util.function.Function<com.github.guoyyit.respgatewayjava.shard.Shard, RespData> action) {
        com.github.guoyyit.respgatewayjava.shard.Shard shard;
        try {
            shard = shardManager.shardForKey(key);
        } catch (RuntimeException e) {
            return completed(RespData.error("ERR", e.getMessage()));
        }
        return submitToShard(shard, epoch, () -> action.apply(shard));
    }

    private CompletableFuture<RespData> requireSameShard(List<String> keys, long epoch,
                                                         MultiShardAction action) {
        int first = ShardRouter.shardIndexForKey(keys.get(0), shardManager.getShardCount());
        for (int i = 1; i < keys.size(); i++) {
            if (ShardRouter.shardIndexForKey(keys.get(i), shardManager.getShardCount()) != first) {
                return completed(RespData.error("CROSSSLOT",
                        "keys in request hash to different shards"));
            }
        }
        com.github.guoyyit.respgatewayjava.shard.Shard shard = shardManager.getShards().get(first);
        return submitToShard(shard, epoch, () -> action.apply(shard, keys));
    }

    private CompletableFuture<RespData> submitToShard(com.github.guoyyit.respgatewayjava.shard.Shard shard,
                                                      long epoch,
                                                      java.util.function.Supplier<RespData> task) {
        java.util.function.Supplier<RespData> guarded = () -> {
            if (epoch != shardManager.getEpoch()) {
                throw new TopologyChangedException();
            }
            return task.get();
        };
        return shard.submit(guarded).thenApply(java.util.function.Function.identity())
                .exceptionally(throwable -> mapFailure(throwable));
    }

    static final class TopologyChangedException extends RuntimeException {
        TopologyChangedException() {
            super("cluster topology changed, command rejected");
        }
    }

    private RespData mapFailure(Throwable throwable) {
        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
        if (cause instanceof CommandException commandException) {
            return RespData.error(commandException.getPrefix(), commandException.getMessage());
        }
        if (cause instanceof NumberFormatException || cause instanceof ArithmeticException) {
            String message = cause instanceof ArithmeticException
                    ? cause.getMessage()
                    : "value is not an integer or out of range";
            return RespData.error("ERR", message);
        }
        if (cause instanceof com.github.guoyyit.respgatewayjava.shard.Shard.ShardUnavailableException) {
            return RespData.error("CLUSTERDOWN", cause.getMessage());
        }
        if (cause instanceof com.github.guoyyit.respgatewayjava.shard.Shard.ShardBusyException) {
            return RespData.error("BUSY", cause.getMessage());
        }
        if (cause instanceof TopologyChangedException) {
            return RespData.error("ERR", cause.getMessage());
        }
        return RespData.error("ERR", "internal error: " + cause);
    }

    private static CompletableFuture<RespData> completed(RespData data) {
        return CompletableFuture.completedFuture(data);
    }
}
