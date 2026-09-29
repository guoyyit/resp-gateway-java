package com.github.guoyyit.respgatewayjava.tests;

import com.github.guoyyit.respgatewayjava.resp.RespData;
import com.github.guoyyit.respgatewayjava.resp.RespParser;
import com.github.guoyyit.respgatewayjava.resp.RespType;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RespParserTest {

    private RespParser.FrameResult feedAll(RespParser parser, byte[] payload) {
        parser.append(ByteBuffer.wrap(payload));
        RespParser.FrameResult result = parser.nextFrame();
        return result;
    }

    @Test
    void parsesAllScalarTypes() {
        RespParser parser = new RespParser(1024);

        RespParser.FrameResult simple = feedAll(parser, "+OK\r\n".getBytes());
        assertEquals(RespParser.Kind.COMPLETE, simple.kind());
        assertEquals(RespType.SIMPLE_STRING, simple.data().type());
        assertEquals("OK", simple.data().stringValue());

        RespParser.FrameResult error = feedAll(parser, "-ERR bad\r\n".getBytes());
        assertEquals(RespType.ERROR, error.data().type());
        assertEquals("ERR bad", error.data().stringValue());

        RespParser.FrameResult integer = feedAll(parser, ":42\r\n".getBytes());
        assertEquals(42L, integer.data().longValue());

        RespParser.FrameResult bulk = feedAll(parser, "$5\r\nhello\r\n".getBytes());
        assertEquals(RespType.BULK_STRING, bulk.data().type());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), bulk.data().bulkBytes());

        RespParser.FrameResult emptyBulk = feedAll(parser, "$0\r\n\r\n".getBytes());
        assertEquals(0, emptyBulk.data().bulkBytes().length);
        assertFalse(emptyBulk.data().isNull());

        RespParser.FrameResult nullBulk = feedAll(parser, "$-1\r\n".getBytes());
        assertTrue(nullBulk.data().isNull());
    }

    @Test
    void parsesArray() {
        RespParser parser = new RespParser(1024);
        byte[] payload = ("*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n").getBytes();
        RespParser.FrameResult result = feedAll(parser, payload);
        assertEquals(RespParser.Kind.COMPLETE, result.kind());
        assertEquals(RespType.ARRAY, result.data().type());
        assertEquals(2, result.data().array().size());
        assertEquals("GET", new String(result.data().array().get(0).bulkBytes()));
    }

    @Test
    void handlesPartialFramesAcrossAppends() {
        RespParser parser = new RespParser(1024);
        byte[] payload = "$5\r\nhello\r\n".getBytes();

        for (int cut = 1; cut < payload.length; cut++) {
            parser.append(ByteBuffer.wrap(payload, 0, cut));
            RespParser.FrameResult partial = parser.nextFrame();
            assertEquals(RespParser.Kind.INCOMPLETE, partial.kind());
            parser.append(ByteBuffer.wrap(payload, cut, payload.length - cut));
            RespParser.FrameResult complete = parser.nextFrame();
            assertEquals(RespParser.Kind.COMPLETE, complete.kind());
            assertArrayEquals("hello".getBytes(), complete.data().bulkBytes());

            RespParser fresh = new RespParser(1024);
            fresh.append(ByteBuffer.wrap(payload));
            fresh.nextFrame();
        }
    }

    @Test
    void handlesStickyPackets() {
        RespParser parser = new RespParser(1024);
        byte[] payload = (":1\r\n:2\r\n:3\r\n").getBytes();
        parser.append(ByteBuffer.wrap(payload));
        for (long expected = 1L; expected <= 3L; expected++) {
            RespParser.FrameResult result = parser.nextFrame();
            assertEquals(RespParser.Kind.COMPLETE, result.kind());
            assertEquals(expected, result.data().longValue());
        }
        assertEquals(RespParser.Kind.INCOMPLETE, parser.nextFrame().kind());
    }

    @Test
    void rejectsOverlongBulkAndRecovers() {
        RespParser parser = new RespParser(8);
        byte[] payload = ("$20\r\n01234567890123456789\r\n+NEXT\r\n").getBytes();
        parser.append(ByteBuffer.wrap(payload));

        RespParser.FrameResult first = parser.nextFrame();
        assertEquals(RespParser.Kind.PROTOCOL_ERROR, first.kind());
        assertTrue(first.isFrameTooLong());

        int drained = 0;
        RespParser.FrameResult recovered = null;
        while (drained++ < 10) {
            recovered = parser.nextFrame();
            if (recovered.kind() == RespParser.Kind.COMPLETE) {
                break;
            }
        }
        assertEquals(RespParser.Kind.COMPLETE, recovered.kind());
        assertEquals("NEXT", recovered.data().stringValue());
    }

    @Test
    void rejectsIllegalFrameAndKeepsParsingState() {
        RespParser parser = new RespParser(1024);
        parser.append(ByteBuffer.wrap("?bad\r\n:7\r\n".getBytes()));
        RespParser.FrameResult error = parser.nextFrame();
        assertEquals(RespParser.Kind.PROTOCOL_ERROR, error.kind());

        RespParser.FrameResult next = parser.nextFrame();
        assertEquals(RespParser.Kind.COMPLETE, next.kind());
        assertEquals(7L, next.data().longValue());
    }

    @Test
    void recoversWhenIllegalPayloadArrivesInPieces() {
        RespParser parser = new RespParser(1024);
        parser.append(ByteBuffer.wrap("?ba".getBytes()));
        assertEquals(RespParser.Kind.INCOMPLETE, parser.nextFrame().kind());
        parser.append(ByteBuffer.wrap("d\r\n:9\r\n".getBytes()));
        assertEquals(RespParser.Kind.PROTOCOL_ERROR, parser.nextFrame().kind());
        RespData next = parser.nextFrame().data();
        assertEquals(9L, next.longValue());
    }
}
