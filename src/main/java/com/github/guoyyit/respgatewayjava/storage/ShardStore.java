package com.github.guoyyit.respgatewayjava.storage;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ShardStore {

    private static final long NO_EXPIRE = 0L;

    public static final class WrongTypeException extends RuntimeException {
        public WrongTypeException(String message) {
            super(message);
        }
    }

    private static final class Entry {
        private final byte[] value;
        private final long expireAtMillis;

        private Entry(byte[] value, long expireAtMillis) {
            this.value = value;
            this.expireAtMillis = expireAtMillis;
        }

        private boolean expired(long nowMillis) {
            return expireAtMillis != NO_EXPIRE && nowMillis >= expireAtMillis;
        }

        private Entry withExpire(long newExpireAtMillis) {
            return new Entry(value, newExpireAtMillis);
        }
    }

    private final int shardIndex;
    private final Map<Key, Entry> data = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ShardStore(int shardIndex) {
        this.shardIndex = shardIndex;
        this.cleaner = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shard-cleaner-" + shardIndex);
            thread.setDaemon(true);
            return thread;
        });
        this.cleaner.scheduleWithFixedDelay(this::sweepExpired, 200L, 200L, TimeUnit.MILLISECONDS);
    }

    public int shardIndex() {
        return shardIndex;
    }

    public byte[] get(byte[] key) {
        long now = System.currentTimeMillis();
        Entry entry = data.get(new Key(key));
        if (entry == null) {
            return null;
        }
        if (entry.expired(now)) {
            data.remove(new Key(key));
            return null;
        }
        return entry.value.clone();
    }

    public void set(byte[] key, byte[] value, long ttlMillis) {
        long expireAt = ttlMillis > 0L ? System.currentTimeMillis() + ttlMillis : NO_EXPIRE;
        data.put(new Key(key), new Entry(value.clone(), expireAt));
    }

    public long del(byte[][] keys) {
        long removed = 0L;
        for (byte[] key : keys) {
            if (data.remove(new Key(key)) != null) {
                removed++;
            }
        }
        return removed;
    }

    public long exists(byte[][] keys) {
        long count = 0L;
        long now = System.currentTimeMillis();
        for (byte[] key : keys) {
            Entry entry = data.get(new Key(key));
            if (entry != null && !entry.expired(now)) {
                count++;
            }
        }
        return count;
    }

    public long expire(byte[] key, long ttlMillis) {
        long now = System.currentTimeMillis();
        Key storedKey = new Key(key);
        Entry entry = data.get(storedKey);
        if (entry == null || entry.expired(now)) {
            data.remove(storedKey);
            return 0L;
        }
        if (ttlMillis <= 0L) {
            data.remove(storedKey);
            return 1L;
        }
        data.put(storedKey, entry.withExpire(now + ttlMillis));
        return 1L;
    }

    public long ttl(byte[] key) {
        long now = System.currentTimeMillis();
        Key storedKey = new Key(key);
        Entry entry = data.get(storedKey);
        if (entry == null || entry.expired(now)) {
            data.remove(storedKey);
            return -2L;
        }
        if (entry.expireAtMillis == NO_EXPIRE) {
            return -1L;
        }
        return Math.max(0L, (entry.expireAtMillis - now + 999L) / 1000L);
    }

    public long persist(byte[] key) {
        long now = System.currentTimeMillis();
        Key storedKey = new Key(key);
        Entry entry = data.get(storedKey);
        if (entry == null || entry.expired(now)) {
            data.remove(storedKey);
            return 0L;
        }
        if (entry.expireAtMillis == NO_EXPIRE) {
            return 0L;
        }
        data.put(storedKey, entry.withExpire(NO_EXPIRE));
        return 1L;
    }

    public long incr(byte[] key, long delta) {
        long now = System.currentTimeMillis();
        Key storedKey = new Key(key);
        Entry existing = data.get(storedKey);
        if (existing != null && existing.expired(now)) {
            existing = null;
            data.remove(storedKey);
        }
        long currentValue = 0L;
        long expireAt = NO_EXPIRE;
        if (existing != null) {
            String text = new String(existing.value, StandardCharsets.UTF_8);
            Long parsed = parseIntegerText(text);
            if (parsed == null) {
                throw new WrongTypeException("ERR value is not an integer or out of range");
            }
            currentValue = parsed;
            expireAt = existing.expireAtMillis;
        }
        long nextValue = Math.addExact(currentValue, delta);
        byte[] encoded = Long.toString(nextValue).getBytes(StandardCharsets.UTF_8);
        data.put(storedKey, new Entry(encoded, expireAt));
        return nextValue;
    }

    public long append(byte[] key, byte[] suffix) {
        long now = System.currentTimeMillis();
        Key storedKey = new Key(key);
        Entry existing = data.get(storedKey);
        if (existing != null && existing.expired(now)) {
            existing = null;
            data.remove(storedKey);
        }
        byte[] current = existing == null ? new byte[0] : existing.value;
        byte[] merged = new byte[current.length + suffix.length];
        System.arraycopy(current, 0, merged, 0, current.length);
        System.arraycopy(suffix, 0, merged, current.length, suffix.length);
        long expireAt = existing == null ? NO_EXPIRE : existing.expireAtMillis;
        data.put(storedKey, new Entry(merged, expireAt));
        return merged.length;
    }

    public int size() {
        return data.size();
    }

    public void shutdown() {
        if (closed.compareAndSet(false, true)) {
            cleaner.shutdownNow();
        }
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        data.forEach((storedKey, entry) -> {
            if (entry.expired(now)) {
                data.remove(storedKey);
            }
        });
    }

    private static Long parseIntegerText(String text) {
        if (text.isEmpty() || text.length() > 20) {
            return null;
        }
        int begin = 0;
        if (text.charAt(0) == '-' || text.charAt(0) == '+') {
            if (text.length() == 1) {
                return null;
            }
            begin = 1;
        }
        long value = 0L;
        for (int index = begin; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character < '0' || character > '9') {
                return null;
            }
            value = value * 10 + (character - '0');
        }
        if (text.charAt(0) == '-') {
            return value == Long.MIN_VALUE ? Long.MIN_VALUE : -value;
        }
        return value;
    }

    private static final class Key {
        private final byte[] bytes;
        private final int hashCode;

        private Key(byte[] bytes) {
            this.bytes = bytes;
            this.hashCode = Arrays.hashCode(bytes);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Arrays.equals(bytes, key.bytes);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
