package com.github.guoyyit.respgatewayjava.resp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RespParser {

    public enum Kind {
        COMPLETE,
        INCOMPLETE,
        PROTOCOL_ERROR
    }

    public static final class FrameResult {
        private final Kind kind;
        private final RespData data;
        private final String errorMessage;
        private final boolean frameTooLong;

        public FrameResult(Kind kind, RespData data, String errorMessage, boolean frameTooLong) {
            this.kind = kind;
            this.data = data;
            this.errorMessage = errorMessage;
            this.frameTooLong = frameTooLong;
        }

        public Kind kind() {
            return kind;
        }

        public RespData data() {
            return data;
        }

        public String errorMessage() {
            return errorMessage;
        }

        public boolean isFrameTooLong() {
            return frameTooLong;
        }
    }

    private static final class ParseOutcome {
        final Kind kind;
        final RespData data;
        final String errorMessage;
        final boolean frameTooLong;
        int consumed;
        long skipBytes;

        ParseOutcome(Kind kind, RespData data, String errorMessage, boolean frameTooLong) {
            this.kind = kind;
            this.data = data;
            this.errorMessage = errorMessage;
            this.frameTooLong = frameTooLong;
        }
    }

    private static final class Cursor {
        private int position;
    }

    private enum SkipMode { NONE, FIXED, TO_CRLF }

    private final long maxFrameBytes;

    private byte[] buffer = new byte[4096];
    private int length;

    private SkipMode skipMode = SkipMode.NONE;
    private long skipRemaining;

    public RespParser(long maxFrameBytes) {
        this.maxFrameBytes = maxFrameBytes;
    }

    public void append(ByteBuffer source) {
        int remaining = source.remaining();
        ensureCapacity(length + remaining);
        source.get(buffer, length, remaining);
        length += remaining;
    }

    public FrameResult nextFrame() {
        if (drainSkippedBytes()) {
            return new FrameResult(Kind.INCOMPLETE, null, null, false);
        }
        if (length == 0) {
            return new FrameResult(Kind.INCOMPLETE, null, null, false);
        }
        Cursor cursor = new Cursor();
        ParseOutcome outcome = parseValue(cursor);
        if (outcome.kind == Kind.COMPLETE) {
            if (outcome.consumed > maxFrameBytes) {
                consume(outcome.consumed);
                return frameTooLongError();
            }
            consume(outcome.consumed);
            return new FrameResult(Kind.COMPLETE, outcome.data, null, false);
        }
        if (outcome.kind == Kind.PROTOCOL_ERROR) {
            consume(outcome.consumed);
            if (outcome.skipBytes > 0) {
                skipMode = SkipMode.FIXED;
                skipRemaining = outcome.skipBytes;
            }
            return new FrameResult(Kind.PROTOCOL_ERROR, null,
                    outcome.errorMessage, outcome.frameTooLong);
        }
        if (length > maxFrameBytes && indexOfCrlf(0) < 0) {
            skipMode = SkipMode.TO_CRLF;
            return frameTooLongError();
        }
        return new FrameResult(Kind.INCOMPLETE, null, null, false);
    }

    public long bufferedBytes() {
        return length;
    }

    private FrameResult frameTooLongError() {
        return new FrameResult(Kind.PROTOCOL_ERROR, null,
                "ERR protocol frame exceeds " + maxFrameBytes + " bytes", true);
    }

    private boolean drainSkippedBytes() {
        if (skipMode == SkipMode.NONE) {
            return false;
        }
        if (skipMode == SkipMode.FIXED) {
            int removable = (int) Math.min(length, skipRemaining);
            consume(removable);
            skipRemaining -= removable;
            if (skipRemaining > 0) {
                return true;
            }
            skipMode = SkipMode.NONE;
            return false;
        }
        int crlf = indexOfCrlf(0);
        if (crlf < 0) {
            consume(length);
            return true;
        }
        consume(crlf + 2);
        skipMode = SkipMode.NONE;
        return false;
    }

    private ParseOutcome parseValue(Cursor cursor) {
        if (cursor.position >= length) {
            return incomplete();
        }
        byte marker = buffer[cursor.position];
        return switch (marker) {
            case '+' -> parseSimple(cursor, false);
            case '-' -> parseSimple(cursor, true);
            case ':' -> parseInteger(cursor);
            case '$' -> parseBulk(cursor);
            case '*' -> parseArray(cursor);
            default -> protocolError(cursor, "ERR unknown RESP type byte");
        };
    }

    private ParseOutcome parseSimple(Cursor cursor, boolean error) {
        int start = cursor.position + 1;
        int lineEnd = indexOfCrlf(start);
        if (lineEnd < 0) {
            return incomplete();
        }
        String value = ascii(start, lineEnd);
        int end = lineEnd + 2;
        cursor.position = end;
        RespData data = error ? RespData.error(value) : RespData.simpleString(value);
        return complete(data, end);
    }

    private ParseOutcome parseInteger(Cursor cursor) {
        int start = cursor.position + 1;
        int lineEnd = indexOfCrlf(start);
        if (lineEnd < 0) {
            return incomplete();
        }
        String token = ascii(start, lineEnd);
        Long parsed = parseStrictLong(token);
        if (parsed == null) {
            return protocolError(cursor, "ERR invalid integer encoding");
        }
        int end = lineEnd + 2;
        cursor.position = end;
        return complete(RespData.integer(parsed), end);
    }

    private ParseOutcome parseBulk(Cursor cursor) {
        int start = cursor.position + 1;
        int lineEnd = indexOfCrlf(start);
        if (lineEnd < 0) {
            return incomplete();
        }
        String token = ascii(start, lineEnd);
        Long declared = parseStrictLong(token);
        if (declared == null || declared < -1L) {
            return protocolError(cursor, "ERR invalid bulk length");
        }
        if (declared == -1L) {
            int end = lineEnd + 2;
            cursor.position = end;
            return complete(RespData.nullBulk(), end);
        }
        int headerEnd = lineEnd + 2;
        long dataSize = declared;
        if (headerEnd + dataSize + 2L > maxFrameBytes) {
            ParseOutcome outcome = new ParseOutcome(Kind.PROTOCOL_ERROR, null,
                    "ERR protocol frame exceeds " + maxFrameBytes + " bytes", true);
            outcome.consumed = headerEnd;
            outcome.skipBytes = dataSize + 2L;
            return outcome;
        }
        int needed = headerEnd + (int) dataSize + 2;
        if (length < needed) {
            return incomplete();
        }
        if (buffer[headerEnd + (int) dataSize] != '\r'
                || buffer[headerEnd + (int) dataSize + 1] != '\n') {
            return protocolError(cursor, "ERR malformed bulk string terminator");
        }
        byte[] copy = new byte[(int) dataSize];
        System.arraycopy(buffer, headerEnd, copy, 0, (int) dataSize);
        cursor.position = needed;
        return complete(RespData.bulk(copy), needed);
    }

    private ParseOutcome parseArray(Cursor cursor) {
        int start = cursor.position + 1;
        int lineEnd = indexOfCrlf(start);
        if (lineEnd < 0) {
            return incomplete();
        }
        String token = ascii(start, lineEnd);
        Long declared = parseStrictLong(token);
        if (declared == null || declared < -1L) {
            return protocolError(cursor, "ERR invalid array length");
        }
        if (declared == -1L) {
            int end = lineEnd + 2;
            cursor.position = end;
            return complete(RespData.nullArray(), end);
        }
        cursor.position = lineEnd + 2;
        List<RespData> items = new ArrayList<>();
        for (long index = 0; index < declared; index++) {
            ParseOutcome element = parseValue(cursor);
            if (element.kind != Kind.COMPLETE) {
                return element;
            }
            items.add(element.data);
            cursor.position = element.consumed;
        }
        return complete(RespData.array(items), cursor.position);
    }

    private ParseOutcome protocolError(Cursor cursor, String message) {
        int crlf = indexOfCrlf(cursor.position + 1);
        if (crlf < 0) {
            return incomplete();
        }
        ParseOutcome outcome = new ParseOutcome(Kind.PROTOCOL_ERROR, null, message, false);
        outcome.consumed = crlf + 2;
        return outcome;
    }

    private ParseOutcome incomplete() {
        return new ParseOutcome(Kind.INCOMPLETE, null, null, false);
    }

    private ParseOutcome complete(RespData data, int consumed) {
        ParseOutcome outcome = new ParseOutcome(Kind.COMPLETE, data, null, false);
        outcome.consumed = consumed;
        return outcome;
    }

    private int indexOfCrlf(int from) {
        for (int index = Math.max(0, from); index < length - 1; index++) {
            if (buffer[index] == '\r' && buffer[index + 1] == '\n') {
                return index;
            }
        }
        return -1;
    }

    private String ascii(int start, int end) {
        return new String(buffer, start, end - start, StandardCharsets.US_ASCII);
    }

    private static Long parseStrictLong(String token) {
        if (token.isEmpty()) {
            return null;
        }
        int begin = 0;
        if (token.charAt(0) == '-') {
            if (token.length() == 1) {
                return null;
            }
            begin = 1;
        }
        if (token.length() - begin > 1 && token.charAt(begin) == '0') {
            return null;
        }
        long value = 0L;
        for (int index = begin; index < token.length(); index++) {
            char character = token.charAt(index);
            if (character < '0' || character > '9') {
                return null;
            }
            value = value * 10 + (character - '0');
        }
        return begin == 1 ? -value : value;
    }

    private void ensureCapacity(int required) {
        if (required <= buffer.length) {
            return;
        }
        int newCapacity = Math.max(buffer.length * 2, required);
        byte[] grown = new byte[newCapacity];
        System.arraycopy(buffer, 0, grown, 0, length);
        buffer = grown;
    }

    private void consume(int count) {
        if (count <= 0) {
            return;
        }
        int remaining = length - count;
        if (remaining > 0) {
            System.arraycopy(buffer, count, buffer, 0, remaining);
        }
        length = remaining;
    }
}
