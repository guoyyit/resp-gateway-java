package com.github.guoyyit.respgatewayjava.tests;

import com.github.guoyyit.respgatewayjava.command.Command;
import com.github.guoyyit.respgatewayjava.command.CommandName;
import com.github.guoyyit.respgatewayjava.resp.RespData;
import com.github.guoyyit.respgatewayjava.resp.RespType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InflightTopologyRejectTest {

    @Test
    void commandRoutedBeforeTopologyChangeIsRejectedUnderRejectPolicy() throws Exception {
        try (TestGateway gateway = TestGateway.start()) {
            int shard = gateway.service().registry().router()
                    .shardIndex("inflightKey".getBytes());
            gateway.service().setShardSlow(shard, true);
            try {
                long oldEpoch = gateway.service().topology().epoch();
                gateway.service().changeTopology(gateway.service().topology().shardCount() + 1,
                        "REJECT");
                Command command = new Command(CommandName.GET, List.of(
                        com.github.guoyyit.respgatewayjava.resp.RespData.bulk(
                                "inflightKey".getBytes())));
                CompletableFuture<RespData> future = gateway.service().registry()
                        .route(command, List.of("inflightKey".getBytes()), oldEpoch);

                RespData reply;
                try {
                    reply = future.get();
                    reply = null;
                } catch (java.util.concurrent.ExecutionException exception) {
                    reply = com.github.guoyyit.respgatewayjava.resp.RespData.error(
                            exception.getCause().getMessage());
                }
                assertEquals(RespType.ERROR, reply.type());
                assertTrue(reply.stringValue().startsWith("CLUSTERDOWN"));
            } finally {
                gateway.service().setShardSlow(shard, false);
            }
        }
    }

    @Test
    void commandsRoutedAfterChangeCompleteNormally() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            int before = gateway.service().topology().shardCount();
            gateway.service().changeTopology(before + 1, "REJECT");
            client.sendCommand("SET", "newEpoch", "ok");
            assertEquals("OK", client.readReply().stringValue());
        }
    }
}
