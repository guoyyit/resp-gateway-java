package com.github.guoyyit.respgatewayjava.cluster;

import java.nio.charset.StandardCharsets;

public final class ShardRouter {

    public static final int SLOT_COUNT = 16384;

    private ShardRouter() {
    }

    public static int slotForKey(String key) {
        byte[] raw = utf8(key);
        int start = 0;
        int end = raw.length;
        int open = -1;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '{') {
                open = i;
                break;
            }
        }
        if (open >= 0) {
            for (int i = open + 1; i < raw.length; i++) {
                if (raw[i] == '}') {
                    if (i > open + 1) {
                        start = open + 1;
                        end = i;
                    }
                    break;
                }
            }
        }
        return crc16(raw, start, end) % SLOT_COUNT;
    }

    public static int shardIndexForSlot(int slot, int shardCount) {
        int index = slot % shardCount;
        return index < 0 ? index + shardCount : index;
    }

    public static int shardIndexForKey(String key, int shardCount) {
        return shardIndexForSlot(slotForKey(key), shardCount);
    }

    static byte[] utf8(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    static int crc16(byte[] data, int start, int end) {
        int crc = 0;
        for (int i = start; i < end; i++) {
            crc ^= (data[i] & 0xff);
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
                crc &= 0xffff;
            }
        }
        return crc;
    }
}
