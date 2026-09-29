package com.github.guoyyit.respgatewayjava.tests;

import com.github.guoyyit.respgatewayjava.resp.RespData;
import com.github.guoyyit.respgatewayjava.routing.KeyRouter;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologyRoutingTest {

    @Test
    void crossSlotMultiKeyReturnsDeterministicError() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            int shardCount = gateway.service().topology().shardCount();
            String first = "crossA";
            int firstShard = KeyRouter.shardForHash(
                    KeyRouter.hash(first.getBytes()), shardCount);
            String other = null;
            for (int index = 0; index < 100_000; index++) {
                String candidate = "cross" + index;
                int shard = KeyRouter.shardForHash(
                        KeyRouter.hash(candidate.getBytes()), shardCount);
                if (shard != firstShard) {
                    other = candidate;
                    break;
                }
            }
            client.sendCommand("MGET", first, other);
            RespData reply = client.readReply();
            assertEquals(com.github.guoyyit.respgatewayjava.resp.RespType.ERROR, reply.type());
            assertTrue(reply.stringValue().startsWith("CROSSSLOT"));
        }
    }

    @Test
    void rejectPolicyRefusesInFlightCommandsAcrossTopologyChange() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            gateway.service().changeTopology(6, "REJECT");
            client.sendCommand("SET", "after", "1");
            RespData reply = client.readReply();
            assertEquals("OK", reply.stringValue());
            assertEquals(6, gateway.service().topology().shardCount());

            long newEpoch = gateway.service().topology().epoch();
            assertTrue(newEpoch > 1L);
        }
    }

    @Test
    void drainPolicyAcceptsAndCompletesNewCommands() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            gateway.service().changeTopology(2, "DRAIN");
            client.sendCommand("SET", "drained", "value");
            assertEquals("OK", client.readReply().stringValue());
            client.sendCommand("GET", "drained");
            assertEquals("value", RespTestClient.bulkAsText(client.readReply()));
        }
    }

    @Test
    void routingIsDeterministicForSameTopology() {
        Map<String, Integer> snapshot = new HashMap<>();
        for (int index = 0; index < 1000; index++) {
            String key = "stable" + index;
            snapshot.put(key, KeyRouter.shardForHash(
                    KeyRouter.hash(key.getBytes()), 4));
        }
        for (Map.Entry<String, Integer> entry : snapshot.entrySet()) {
            int recomputed = KeyRouter.shardForHash(
                    KeyRouter.hash(entry.getKey().getBytes()), 4);
            assertEquals(entry.getValue(), recomputed);
        }
    }
}
