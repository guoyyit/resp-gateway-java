package com.github.guoyyit.respgatewayjava.resp;

import java.util.List;

public final class RespData {

    private final RespType type;
    private final String stringValue;
    private final long longValue;
    private final byte[] bulkBytes;
    private final List<RespData> array;
    private final boolean isNull;

    public RespData(RespType type) {
        this(type, null, 0L, null, null, false);
    }

    private RespData(RespType type, String stringValue, long longValue,
                     byte[] bulkBytes, List<RespData> array, boolean isNull) {
        this.type = type;
        this.stringValue = stringValue;
        this.longValue = longValue;
        this.bulkBytes = bulkBytes;
        this.array = array;
        this.isNull = isNull;
    }

    public RespType type() {
        return type;
    }

    public String stringValue() {
        return stringValue;
    }

    public long longValue() {
        return longValue;
    }

    public byte[] bulkBytes() {
        return bulkBytes;
    }

    public List<RespData> array() {
        return array;
    }

    public boolean isNull() {
        return isNull;
    }

    public static RespData simpleString(String value) {
        return new RespData(RespType.SIMPLE_STRING, value, 0L, null, null, false);
    }

    public static RespData error(String value) {
        return new RespData(RespType.ERROR, value, 0L, null, null, false);
    }

    public static RespData integer(long value) {
        return new RespData(RespType.INTEGER, null, value, null, null, false);
    }

    public static RespData bulk(byte[] value) {
        return new RespData(RespType.BULK_STRING, null, 0L, value, null, false);
    }

    public static RespData nullBulk() {
        return new RespData(RespType.BULK_STRING, null, 0L, null, null, true);
    }

    public static RespData array(List<RespData> items) {
        return new RespData(RespType.ARRAY, null, 0L, null, List.copyOf(items), false);
    }

    public static RespData nullArray() {
        return new RespData(RespType.ARRAY, null, 0L, null, null, true);
    }
}
