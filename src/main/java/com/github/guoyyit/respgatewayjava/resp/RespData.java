package com.github.guoyyit.respgatewayjava.resp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class RespData {

    private final RespType type;
    private final long integer;
    private final byte[] bytes;
    private final boolean isNull;
    private final List<RespData> array;

    public RespData(RespType type) {
        this.type = type;
        this.integer = 0;
        this.bytes = null;
        this.isNull = false;
        this.array = List.of();
    }

    private RespData(RespType type, long integer, byte[] bytes, boolean isNull, List<RespData> array) {
        this.type = type;
        this.integer = integer;
        this.bytes = bytes;
        this.isNull = isNull;
        this.array = array == null ? List.of() : List.copyOf(array);
    }

    public RespType getType() { return type; }
    public byte[] getBytes() { return bytes; }
    public long getInteger() { return integer; }
    public List<RespData> getArray() { return array; }
    public boolean isNull() { return isNull; }

    public static RespData simpleString(String value) {
        return new RespData(RespType.SIMPLE_STRING, 0, value.getBytes(StandardCharsets.UTF_8), false, null);
    }

    public static RespData error(String value) {
        return new RespData(RespType.ERROR, 0, value.getBytes(StandardCharsets.UTF_8), false, null);
    }

    public static RespData error(String prefix, String message) {
        return error(prefix + ' ' + message);
    }

    public static RespData integer(long value) {
        return new RespData(RespType.INTEGER, value, null, false, null);
    }

    public static RespData bulkString(byte[] value) {
        if (value == null) {
            return nullBulk();
        }
        return new RespData(RespType.BULK_STRING, 0, value, false, null);
    }

    public static RespData nullBulk() {
        return new RespData(RespType.BULK_STRING, 0, null, true, null);
    }

    public static RespData array(List<RespData> items) {
        return new RespData(RespType.ARRAY, 0, null, items == null, items == null ? null : List.copyOf(items));
    }

    public ByteBuffer encode() {
        byte[] out = encodeToBytes();
        return ByteBuffer.wrap(out);
    }

    private byte[] encodeToBytes() {
        byte[][] parts;
        switch (type) {
            case SIMPLE_STRING:
                return concat(new byte[]{'+'}, bytes, CRLF);
            case ERROR:
                return concat(new byte[]{'-'}, bytes, CRLF);
            case INTEGER:
                return concat(new byte[]{':'},
                        Long.toString(integer).getBytes(StandardCharsets.US_ASCII), CRLF);
            case BULK_STRING:
                if (isNull) {
                    return "$-1\r\n".getBytes(StandardCharsets.US_ASCII);
                }
                return concat(
                        new byte[]{'$'},
                        Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII),
                        CRLF,
                        bytes,
                        CRLF);
            case ARRAY:
                if (isNull) {
                    return "*-1\r\n".getBytes(StandardCharsets.US_ASCII);
                }
                parts = new byte[array.size()][];
                int total = 0;
                for (int i = 0; i < array.size(); i++) {
                    parts[i] = array.get(i).encodeToBytes();
                    total += parts[i].length;
                }
                byte[] header = concat(new byte[]{'*'},
                        Integer.toString(array.size()).getBytes(StandardCharsets.US_ASCII), CRLF);
                byte[] result = new byte[header.length + total];
                System.arraycopy(header, 0, result, 0, header.length);
                int pos = header.length;
                for (byte[] part : parts) {
                    System.arraycopy(part, 0, result, pos, part.length);
                    pos += part.length;
                }
                return result;
            default:
                throw new IllegalStateException("unknown type " + type);
        }
    }

    private static final byte[] CRLF = {'\r', '\n'};

    private static byte[] concat(byte[]... pieces) {
        int total = 0;
        for (byte[] piece : pieces) {
            total += piece.length;
        }
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] piece : pieces) {
            System.arraycopy(piece, 0, result, pos, piece.length);
            pos += piece.length;
        }
        return result;
    }
}
