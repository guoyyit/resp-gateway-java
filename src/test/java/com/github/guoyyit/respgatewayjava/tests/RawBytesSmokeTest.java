package com.github.guoyyit.respgatewayjava.tests;

import com.github.guoyyit.respgatewayjava.resp.RespData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RawBytesSmokeTest {

    @Test
    void rawByteStreamRoundTrip() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            client.sendRaw("*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\n1\r\n".getBytes());
            assertEquals("OK", client.readReply().stringValue());

            client.sendRaw(("*2\r\n$4\r\nINCR\r\n$1\r\na\r\n").getBytes());
            assertEquals(2L, client.readReply().longValue());

            client.sendRaw(("*2\r\n$3\r\nGET\r\n$1\r\na\r\n").getBytes());
            assertEquals("2", RespTestClient.bulkAsText(client.readReply()));
        }
    }
}
