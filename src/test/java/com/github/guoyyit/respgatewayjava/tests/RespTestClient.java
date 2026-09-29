package com.github.guoyyit.respgatewayjava.tests;

import com.github.guoyyit.respgatewayjava.resp.RespData;
import com.github.guoyyit.respgatewayjava.resp.RespType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class RespTestClient implements AutoCloseable {

    private final Socket socket;
    private final OutputStream output;
    private final InputStream input;

    RespTestClient(int port) throws IOException {
        this(port, 0);
    }

    RespTestClient(int port, int readTimeoutMillis) throws IOException {
        this.socket = new Socket("127.0.0.1", port);
        this.socket.setTcpNoDelay(true);
        if (readTimeoutMillis > 0) {
            this.socket.setSoTimeout(readTimeoutMillis);
        }
        this.output = socket.getOutputStream();
        this.input = socket.getInputStream();
    }

    void sendRaw(byte[] payload) throws IOException {
        output.write(payload);
        output.flush();
    }

    void sendCommand(String... parts) throws IOException {
        sendRaw(encodeCommand(parts));
    }

    static byte[] encodeCommand(String... parts) {
        StringBuilder builder = new StringBuilder();
        builder.append('*').append(parts.length).append("\r\n");
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            builder.append('$').append(bytes.length).append("\r\n");
            builder.append(part).append("\r\n");
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    byte[] readRaw(int count) throws IOException {
        byte[] bytes = input.readNBytes(count);
        if (bytes.length != count) {
            throw new IOException("expected " + count + " bytes but got " + bytes.length);
        }
        return bytes;
    }

    RespData readReply() throws IOException {
        return new Reader(input).readValue();
    }

    List<RespData> readReplies(int count) throws IOException {
        Reader reader = new Reader(input);
        List<RespData> replies = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            replies.add(reader.readValue());
        }
        return replies;
    }

    boolean isClosed() {
        return socket.isClosed();
    }

    Socket rawSocket() {
        return socket;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private static final class Reader {
        private final InputStream input;

        private Reader(InputStream input) {
            this.input = input;
        }

        private RespData readValue() throws IOException {
            int marker = input.read();
            if (marker == -1) {
                throw new IOException("connection closed by server");
            }
            String line = readLine();
            return switch (marker) {
                case '+' -> RespData.simpleString(line);
                case '-' -> RespData.error(line);
                case ':' -> RespData.integer(Long.parseLong(line));
                case '$' -> readBulk(Long.parseLong(line));
                case '*' -> readArray(Long.parseLong(line));
                default -> throw new IOException("unexpected RESP marker " + (char) marker);
            };
        }

        private RespData readBulk(long length) throws IOException {
            if (length == -1L) {
                return RespData.nullBulk();
            }
            byte[] bytes = input.readNBytes((int) length);
            readCrlf();
            return RespData.bulk(bytes);
        }

        private RespData readArray(long length) throws IOException {
            if (length == -1L) {
                return RespData.nullArray();
            }
            List<RespData> items = new ArrayList<>();
            for (long index = 0; index < length; index++) {
                items.add(readValue());
            }
            return RespData.array(items);
        }

        private String readLine() throws IOException {
            ByteArrayLine line = new ByteArrayLine();
            int previous = -1;
            int current;
            while ((current = input.read()) != -1) {
                if (previous == '\r' && current == '\n') {
                    line.trimTrailingCr();
                    return line.asString();
                }
                if (previous != -1) {
                    line.append(previous);
                }
                previous = current;
            }
            throw new IOException("unexpected end of stream");
        }

        private void readCrlf() throws IOException {
            int first = input.read();
            int second = input.read();
            if (first != '\r' || second != '\n') {
                throw new IOException("expected CRLF");
            }
        }
    }

    private static final class ByteArrayLine {
        private byte[] bytes = new byte[64];
        private int length;

        private void append(int value) {
            if (length == bytes.length) {
                byte[] grown = new byte[bytes.length * 2];
                System.arraycopy(bytes, 0, grown, 0, length);
                bytes = grown;
            }
            bytes[length++] = (byte) value;
        }

        private void trimTrailingCr() {
            if (length > 0 && bytes[length - 1] == '\r') {
                length--;
            }
        }

        private String asString() {
            return new String(bytes, 0, length, StandardCharsets.US_ASCII);
        }
    }

    static String bulkAsText(RespData data) {
        if (data.type() != RespType.BULK_STRING) {
            throw new IllegalStateException("expected bulk but got " + data.type());
        }
        return new String(data.bulkBytes(), StandardCharsets.UTF_8);
    }
}
