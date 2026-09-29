package com.github.guoyyit.respgatewayjava.shard;

public class Entry {

    public static final long NO_WALL_EXPIRY = Long.MAX_VALUE;

    private final byte[] value;
    private final long expireAtNanos;
    private final long expireAtEpochMillis;

    public Entry(byte[] value, long expireAtNanos) {
        this(value, expireAtNanos, NO_WALL_EXPIRY);
    }

    public Entry(byte[] value, long expireAtNanos, long expireAtEpochMillis) {
        this.value = value;
        this.expireAtNanos = expireAtNanos;
        this.expireAtEpochMillis = expireAtEpochMillis;
    }

    public byte[] getValue() { return value; }

    public long getExpireAtNanos() { return expireAtNanos; }

    public long getExpireAtEpochMillis() { return expireAtEpochMillis; }
}
