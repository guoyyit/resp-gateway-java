package com.github.guoyyit.respgatewayjava.routing;

public final class Topology {

    private final int shardCount;
    private final long epoch;

    public Topology(int shardCount, long epoch) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        this.shardCount = shardCount;
        this.epoch = epoch;
    }

    public int shardCount() {
        return shardCount;
    }

    public long epoch() {
        return epoch;
    }
}
