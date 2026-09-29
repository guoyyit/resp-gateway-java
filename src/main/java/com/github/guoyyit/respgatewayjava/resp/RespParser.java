package com.github.guoyyit.respgatewayjava.resp;

import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RespParser {

    private final int maxFrameBytes;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final List<RespData> completes = new ArrayList<>();
    private byte[] data = new byte[0];
    private int pos;
    private boolean protocolError;
    private int skipBytes;

    public RespParser(int maxFrameBytes) {
        this.maxFrameBytes = maxFrameBytes;
    }

    public void feed(ByteBuffer buffer) {
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        this.buffer.write(chunk, 0, chunk.length);
    }

    public List<RespData> drainCompletes() {
        if (protocolError) {
            resyncToNextLine();
            protocolError = false;
        }
        data = buffer.toByteArray();
        pos = 0;
        parseLoop();
        compactLeftover();
        if (completes.isEmpty()) {
            return List.of();
        }
        List<RespData> result = new ArrayList<>(completes);
        completes.clear();
        return result;
    }

    public boolean hasProtocolError() {
        return protocolError;
    }

    public void clearProtocolError() {
        protocolError = false;
    }

    private void parseLoop() {
        while (!protocolError && pos < data.length) {
            if (skipBytes > 0) {
                skipOverflow();
                if (skipBytes > 0 || protocolError) {
                    return;
                }
            }
            int mark = pos;
            RespData value;
            try {
                value = parseValue();
            } catch (NeedMoreData e) {
                pos = mark;
                return;
            }
            if (value == null) {
                pos = mark;
                return;
            }
            completes.add(value);
        }
    }

    private RespData parseValue() {
        if (pos >= data.length) {
            return null;
        }
        byte type = data[pos++];
        return switch (type) {
            case '+' -> RespData.simpleString(readLineString());
            case '-' -> RespData.error(readLineString());
            case ':' -> {
                String line = readLineString();
                Long parsed = parseSignedLong(line);
                if (parsed == null) {
                    throw protocolError();
                }
                yield RespData.integer(parsed);
            }
            case '$' -> parseBulk();
            case '*' -> parseArray();
            default -> throw protocolError();
        };
    }

    private RespData parseBulk() {
        String line = readLineString();
        Long length = parseSignedLong(line);
        if (length == null || length < -1) {
            throw protocolError();
        }
        if (length == -1) {
            return RespData.nullBulk();
        }
        if (length > maxFrameBytes) {
            protocolError = true;
            skipBytes = length.intValue() + 2;
            skipOverflow();
            return null;
        }
        int len = length.intValue();
        ensureAvailable(len + 2);
        byte[] value = new byte[len];
        System.arraycopy(data, pos, value, 0, len);
        pos += len;
        if (data[pos] != '\r' || data[pos + 1] != '\n') {
            throw protocolError();
        }
        pos += 2;
        return RespData.bulkString(value);
    }

    private RespData parseArray() {
        String line = readLineString();
        Long length = parseSignedLong(line);
        if (length == null || length < -1) {
            throw protocolError();
        }
        if (length == -1) {
            return RespData.array(null);
        }
        if (length > maxFrameBytes) {
            throw protocolError();
        }
        int len = length.intValue();
        List<RespData> items = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            RespData item = parseValue();
            if (item == null) {
                return null;
            }
            items.add(item);
        }
        return RespData.array(items);
    }

    private String readLineString() {
        int end = findCrlf(pos);
        if (end < 0) {
            int lf = findLf(pos);
            if (lf < 0) {
                if (data.length - pos > maxFrameBytes) {
                    throw protocolError();
                }
                throw NeedMoreData.INSTANCE;
            }
            String result = new String(data, pos, lf - pos, StandardCharsets.UTF_8);
            pos = lf + 1;
            return result;
        }
        String result = new String(data, pos, end - pos, StandardCharsets.UTF_8);
        pos = end + 2;
        return result;
    }

    private void ensureAvailable(int bytes) {
        if (data.length - pos < bytes) {
            throw NeedMoreData.INSTANCE;
        }
    }

    private int findCrlf(int from) {
        for (int i = from; i < data.length - 1; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private int findLf(int from) {
        for (int i = from; i < data.length; i++) {
            if (data[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private Long parseSignedLong(String text) {
        if (text.isEmpty()) {
            return null;
        }
        int start = 0;
        if (text.charAt(0) == '+' || text.charAt(0) == '-') {
            start = 1;
        }
        if (start == text.length()) {
            return null;
        }
        long value = 0;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < '0' || ch > '9') {
                return null;
            }
            long digit = ch - '0';
            if (value > (Long.MAX_VALUE - digit) / 10) {
                return null;
            }
            value = value * 10 + digit;
        }
        return text.charAt(0) == '-' ? -value : value;
    }

    private IllegalStateException protocolError() {
        protocolError = true;
        return new IllegalStateException("protocol error");
    }

    private void skipOverflow() {
        int available = data.length - pos;
        if (available <= 0) {
            return;
        }
        int consumed = Math.min(available, skipBytes);
        pos += consumed;
        skipBytes -= consumed;
        if (skipBytes == 0) {
            return;
        }
    }

    private void resyncToNextLine() {
        byte[] current = buffer.toByteArray();
        int drop = 0;
        while (drop < current.length && current[drop] != '\n') {
            drop++;
        }
        if (drop < current.length) {
            drop++;
        }
        buffer.reset();
        if (drop < current.length) {
            buffer.write(current, drop, current.length - drop);
        }
    }

    private void compactLeftover() {
        if (pos == 0) {
            return;
        }
        if (pos >= data.length) {
            buffer.reset();
        } else {
            byte[] left = new byte[data.length - pos];
            System.arraycopy(data, pos, left, 0, left.length);
            buffer.reset();
            buffer.write(left, 0, left.length);
        }
    }

    private static final class NeedMoreData extends RuntimeException {
        private static final NeedMoreData INSTANCE = new NeedMoreData();

        private NeedMoreData() {
            super(null, null, false, false);
        }
    }
}
