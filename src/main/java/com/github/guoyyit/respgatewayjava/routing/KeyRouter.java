package com.github.guoyyit.respgatewayjava.routing;

public final class KeyRouter {

    private final Topology topology;

    public KeyRouter(Topology topology) {
        this.topology = topology;
    }

    public int shardIndex(byte[] key) {
        return shardForHash(hash(key), topology.shardCount());
    }

    public static int shardForHash(int hash, int shardCount) {
        int index = hash % shardCount;
        return index < 0 ? index + shardCount : index;
    }

    public static int hash(byte[] key) {
        int crc = 0xFFFF;
        for (byte raw : key) {
            crc ^= raw & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 1) == 1) {
                    crc = (crc >>> 1) ^ 0xA001;
                } else {
                    crc >>>= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }
}
