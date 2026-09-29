package com.github.guoyyit.respgatewayjava.resp;

public final class RespWriter {

    private static final byte[] CRLF = {'\r', '\n'};

    private RespWriter() {
    }

    public static byte[] encode(RespData data) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeTo(out, data);
        return out.toByteArray();
    }

    private static void writeTo(java.io.ByteArrayOutputStream out, RespData data) {
        switch (data.type()) {
            case SIMPLE_STRING -> {
                out.write('+');
                writeAscii(out, data.stringValue());
                out.write(CRLF, 0, 2);
            }
            case ERROR -> {
                out.write('-');
                writeAscii(out, data.stringValue());
                out.write(CRLF, 0, 2);
            }
            case INTEGER -> {
                out.write(':');
                writeAscii(out, Long.toString(data.longValue()));
                out.write(CRLF, 0, 2);
            }
            case BULK_STRING -> {
                out.write('$');
                if (data.isNull()) {
                    writeAscii(out, "-1");
                    out.write(CRLF, 0, 2);
                } else {
                    byte[] bytes = data.bulkBytes();
                    writeAscii(out, Integer.toString(bytes.length));
                    out.write(CRLF, 0, 2);
                    out.write(bytes, 0, bytes.length);
                    out.write(CRLF, 0, 2);
                }
            }
            case ARRAY -> {
                out.write('*');
                if (data.isNull()) {
                    writeAscii(out, "-1");
                    out.write(CRLF, 0, 2);
                } else {
                    java.util.List<RespData> items = data.array();
                    writeAscii(out, Integer.toString(items.size()));
                    out.write(CRLF, 0, 2);
                    for (RespData item : items) {
                        writeTo(out, item);
                    }
                }
            }
        }
    }

    private static void writeAscii(java.io.ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
    }
}
