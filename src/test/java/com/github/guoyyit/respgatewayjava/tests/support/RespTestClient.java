package com.github.guoyyit.respgatewayjava.tests.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RespTestClient implements AutoCloseable {

    private final Socket socket;
    private final OutputStream output;
    private final InputStream input;

    public RespTestClient(int port) throws IOException {
        this(port, 5000);
    }

    public RespTestClient(int port, int timeoutMs) throws IOException {
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
        this.socket.setSoTimeout(timeoutMs);
        this.socket.setTcpNoDelay(true);
        this.output = socket.getOutputStream();
        this.input = socket.getInputStream();
    }

    public void sendRaw(byte[] bytes) throws IOException {
        output.write(bytes);
        output.flush();
    }

    public void sendRaw(String text) throws IOException {
        sendRaw(text.getBytes(StandardCharsets.UTF_8));
    }

    public void sendCommand(String... parts) throws IOException {
        sendRaw(encode(parts));
    }

    public static byte[] encode(String... parts) {
        StringBuilder builder = new StringBuilder();
        builder.append('*').append(parts.length).append("\r\n");
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            builder.append('$').append(bytes.length).append("\r\n");
            builder.append(part).append("\r\n");
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    public Object readReply() throws IOException {
        return readValue();
    }

    public byte[] readBulkBytes() throws IOException {
        int type = input.read();
        if (type != '$') {
            throw new IOException("expected bulk string, got " + (char) type);
        }
        String line = readLine();
        int length = Integer.parseInt(line);
        if (length == -1) {
            return null;
        }
        byte[] body = readExact(length);
        readExact(2);
        return body;
    }

    private Object readValue() throws IOException {
        int type = input.read();
        if (type == -1) {
            throw new IOException("connection closed by peer");
        }
        String line = readLine();
        return switch ((char) type) {
            case '+' -> line;
            case '-' -> new ErrorReply(line);
            case ':' -> Long.parseLong(line);
            case '$' -> {
                int length = Integer.parseInt(line);
                if (length == -1) {
                    yield null;
                }
                byte[] body = readExact(length);
                readExact(2);
                yield new String(body, StandardCharsets.UTF_8);
            }
            case '*' -> {
                int length = Integer.parseInt(line);
                if (length == -1) {
                    yield null;
                }
                List<Object> values = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    values.add(readValue());
                }
                yield values;
            }
            default -> throw new IOException("unexpected reply type: " + (char) type);
        };
    }

    private String readLine() throws IOException {
        ByteArrayBuilder builder = new ByteArrayBuilder();
        int previous = -1;
        while (true) {
            int b = input.read();
            if (b == -1) {
                throw new IOException("eof while reading line");
            }
            if (previous == '\r' && b == '\n') {
                break;
            }
            if (previous != -1) {
                builder.append(previous);
            }
            previous = b;
        }
        return new String(builder.toByteArray(), StandardCharsets.UTF_8);
    }

    private byte[] readExact(int length) throws IOException {
        byte[] buffer = new byte[length];
        int read = 0;
        while (read < length) {
            int n = input.read(buffer, read, length - read);
            if (n == -1) {
                throw new IOException("eof while reading bulk body");
            }
            read += n;
        }
        return buffer;
    }

    public boolean isClosedByPeer() {
        try {
            socket.setSoTimeout(300);
            int b = input.read();
            return b == -1;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                socket.setSoTimeout(5000);
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    public record ErrorReply(String message) {
        @Override
        public String toString() {
            return "ERROR " + message;
        }
    }

    private static final class ByteArrayBuilder {
        private byte[] buffer = new byte[64];
        private int size;

        private void append(int b) {
            if (size == buffer.length) {
                byte[] grown = new byte[buffer.length * 2];
                System.arraycopy(buffer, 0, grown, 0, size);
                buffer = grown;
            }
            buffer[size++] = (byte) b;
        }

        private byte[] toByteArray() {
            byte[] copy = new byte[size];
            System.arraycopy(buffer, 0, copy, 0, size);
            return copy;
        }
    }
}
