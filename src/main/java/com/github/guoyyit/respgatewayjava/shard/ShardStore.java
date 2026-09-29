package com.github.guoyyit.respgatewayjava.shard;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ShardStore {

    public static final long NO_EXPIRE = Long.MAX_VALUE;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public ShardStore() {
    }

    public Entry get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (isExpired(entry)) {
            entries.remove(key, entry);
            return null;
        }
        return entry;
    }

    public void put(String key, byte[] value, long expireAtNanos) {
        entries.put(key, createEntry(value, expireAtNanos));
    }

    public void put(String key, byte[] value, long expireAtNanos, long expireAtEpochMillis) {
        entries.put(key, new Entry(value, expireAtNanos, expireAtEpochMillis));
    }

    public boolean delete(String key) {
        return entries.remove(key) != null;
    }

    public long deleteMany(Iterable<String> keys) {
        long removed = 0;
        for (String key : keys) {
            if (delete(key)) {
                removed++;
            }
        }
        return removed;
    }

    public long countExisting(Iterable<String> keys) {
        long count = 0;
        for (String key : keys) {
            if (get(key) != null) {
                count++;
            }
        }
        return count;
    }

    public boolean expireAt(String key, long expireAtNanos) {
        return entries.computeIfPresent(key, (k, entry) -> {
            if (isExpired(entry)) {
                return null;
            }
            return createEntry(entry.getValue(), expireAtNanos);
        }) != null;
    }

    public Long ttlMillis(String key) {
        Entry entry = get(key);
        if (entry == null) {
            return -2L;
        }
        if (entry.getExpireAtNanos() == NO_EXPIRE) {
            return -1L;
        }
        long remainingMillis = (entry.getExpireAtNanos() - System.nanoTime()) / 1_000_000L;
        return remainingMillis < 0 ? -2L : remainingMillis;
    }

    public boolean persist(String key) {
        return entries.computeIfPresent(key, (k, entry) -> {
            if (isExpired(entry)) {
                return null;
            }
            if (entry.getExpireAtNanos() == NO_EXPIRE) {
                return entry;
            }
            return new Entry(entry.getValue(), NO_EXPIRE);
        }) != null;
    }

    public long increment(String key, long delta, long initialTtlNanos) {
        long[] result = new long[1];
        entries.compute(key, (k, entry) -> {
            long current = 0;
            if (entry != null && !isExpired(entry)) {
                long[] parsedHolder = new long[1];
                if (!parseInteger(entry.getValue(), parsedHolder)) {
                    throw new NumberFormatException("value is not an integer");
                }
                current = parsedHolder[0];
            }
            long updated;
            if (delta >= 0) {
                if (current > Long.MAX_VALUE - delta) {
                    throw new ArithmeticException("increment or decrement would overflow");
                }
                updated = current + delta;
            } else {
                if (current < Long.MIN_VALUE - delta) {
                    throw new ArithmeticException("increment or decrement would overflow");
                }
                updated = current + delta;
            }
            result[0] = updated;
            long expireAt;
            if (entry == null || isExpired(entry)) {
                expireAt = initialTtlNanos <= 0 || initialTtlNanos == NO_EXPIRE
                        ? NO_EXPIRE : System.nanoTime() + initialTtlNanos;
            } else {
                expireAt = entry.getExpireAtNanos();
            }
            return preservedEntry(
                    Long.toString(updated).getBytes(StandardCharsets.US_ASCII), entry, expireAt);
        });
        return result[0];
    }

    public int append(String key, byte[] suffix, long initialTtlNanos) {
        int[] length = new int[1];
        entries.compute(key, (k, entry) -> {
            byte[] base = (entry == null || isExpired(entry)) ? new byte[0] : entry.getValue();
            byte[] merged = new byte[base.length + suffix.length];
            System.arraycopy(base, 0, merged, 0, base.length);
            System.arraycopy(suffix, 0, merged, base.length, suffix.length);
            length[0] = merged.length;
            long expireAt;
            if (entry == null || isExpired(entry)) {
                expireAt = initialTtlNanos <= 0 || initialTtlNanos == NO_EXPIRE
                        ? NO_EXPIRE : System.nanoTime() + initialTtlNanos;
            } else {
                expireAt = entry.getExpireAtNanos();
            }
            return preservedEntry(merged, entry, expireAt);
        });
        return length[0];
    }

    private static Entry createEntry(byte[] value, long expireAtNanos) {
        if (expireAtNanos == NO_EXPIRE) {
            return new Entry(value, NO_EXPIRE, Entry.NO_WALL_EXPIRY);
        }
        long remainingNanos = expireAtNanos - System.nanoTime();
        long wallExpiry = System.currentTimeMillis() + Math.max(0, remainingNanos / 1_000_000L);
        return new Entry(value, expireAtNanos, wallExpiry);
    }

    private static Entry preservedEntry(byte[] value, Entry previous, long expireAtNanos) {
        if (previous == null || isExpired(previous)) {
            return createEntry(value, expireAtNanos);
        }
        return new Entry(value, expireAtNanos, previous.getExpireAtEpochMillis());
    }

    public static Entry restoreEntry(byte[] value, long expireAtEpochMillis) {
        if (expireAtEpochMillis == Entry.NO_WALL_EXPIRY) {
            return new Entry(value, NO_EXPIRE, Entry.NO_WALL_EXPIRY);
        }
        long remainingNanos = Math.max(0, expireAtEpochMillis - System.currentTimeMillis()) * 1_000_000L;
        return new Entry(value, System.nanoTime() + remainingNanos, expireAtEpochMillis);
    }

    public Map<String, Entry> snapshot() {
        Map<String, Entry> copy = new LinkedHashMap<>();
        entries.forEach((key, entry) -> {
            if (!isExpired(entry)) {
                copy.put(key, entry);
            }
        });
        return copy;
    }

    public void restore(String key, Entry entry) {
        entries.put(key, entry);
    }

    private static boolean isExpired(Entry entry) {
        return entry.getExpireAtNanos() != NO_EXPIRE
                && entry.getExpireAtNanos() <= System.nanoTime();
    }

    private static boolean parseInteger(byte[] value, long[] out) {
        String text = new String(value, StandardCharsets.US_ASCII).trim();
        if (text.isEmpty()) {
            return false;
        }
        int start = 0;
        if (text.charAt(0) == '+' || text.charAt(0) == '-') {
            start = 1;
        }
        if (start == text.length()) {
            return false;
        }
        java.math.BigInteger parsed = java.math.BigInteger.ZERO;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
            parsed = parsed.multiply(java.math.BigInteger.TEN)
                    .add(java.math.BigInteger.valueOf(ch - '0'));
        }
        if (text.charAt(0) == '-') {
            parsed = parsed.negate();
        }
        if (parsed.compareTo(java.math.BigInteger.valueOf(Long.MIN_VALUE)) < 0
                || parsed.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return false;
        }
        out[0] = parsed.longValueExact();
        return true;
    }
}
