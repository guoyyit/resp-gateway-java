package com.github.guoyyit.respgatewayjava.tests;

import com.github.guoyyit.respgatewayjava.resp.RespData;
import com.github.guoyyit.respgatewayjava.resp.RespType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardFailureTest {

    @Test
    void forcedDownShardReturnsExplicitErrorThenRecovers() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            int shard = pickShardWithKey(gateway, "failKey");
            gateway.service().forceShardDown(shard);
            try {
                client.sendCommand("SET", "failKey", "v");
                RespData error = client.readReply();
                assertEquals(RespType.ERROR, error.type());
                assertTrue(error.stringValue().startsWith("CLUSTERDOWN"),
                        "unexpected reply " + error.stringValue());
            } finally {
                gateway.service().restoreShard(shard);
            }

            client.sendCommand("SET", "failKey", "v2");
            assertEquals("OK", client.readReply().stringValue());
            client.sendCommand("GET", "failKey");
            assertEquals("v2", RespTestClient.bulkAsText(client.readReply()));
        }
    }

    @Test
    void repliesRemainOrderedDuringFailure() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            int shard = pickShardWithKey(gateway, "ordKey");
            gateway.service().forceShardDown(shard);
            try {
                for (int index = 0; index < 10; index++) {
                    client.sendCommand("GET", "ordKey" + (index % 2 == 0 ? "" : "X"));
                }
                for (int index = 0; index < 10; index++) {
                    RespData reply = client.readReply();
                    String key = (index % 2 == 0) ? "ordKey" : "ordKeyX";
                    int targetShard = gateway.service().registry().router()
                            .shardIndex(key.getBytes());
                    if (targetShard == shard) {
                        assertEquals(RespType.ERROR, reply.type());
                        assertTrue(reply.stringValue().startsWith("CLUSTERDOWN"));
                    } else {
                        assertTrue(reply.isNull());
                    }
                }
            } finally {
                gateway.service().restoreShard(shard);
            }
        }
    }

    @Test
    void healthMonitorAutoRejoinsRecoveredShard() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient client = new RespTestClient(gateway.port())) {
            int shard = pickShardWithKey(gateway, "healthKey");
            gateway.service().shard(shard).setHealthDown(true);

            client.sendCommand("GET", "healthKey");
            assertTrue(client.readReply().stringValue().startsWith("CLUSTERDOWN"));

            Thread.sleep(900L);

            client.sendCommand("SET", "healthKey", "alive");
            assertEquals("OK", client.readReply().stringValue());
        }
    }

    @Test
    void saturatedShardReturnsBusyAndRecovers() throws Exception {
        try (TestGateway gateway = TestGateway.start();
             RespTestClient blocker = new RespTestClient(gateway.port(), 10000);
             RespTestClient client = new RespTestClient(gateway.port(), 10000)) {
            int shard = pickShardWithKey(gateway, "busyKey");
            gateway.service().setShardSlow(shard, true);
            gateway.service().shard(shard).setSlowDelayMillis(20L);
            try {
                for (int index = 0; index < 70; index++) {
                    blocker.sendCommand("SET", sameShardKey(gateway, shard, "block" + index), "v");
                }
                long deadline = System.currentTimeMillis() + 5000L;
                RespData busyReply = null;
                while (deadline > System.currentTimeMillis()) {
                    client.sendCommand("SET", sameShardKey(gateway, shard, "probe"), "v");
                    RespData reply = client.readReply();
                    if (reply.type() == RespType.ERROR
                            && reply.stringValue().startsWith("BUSY")) {
                        busyReply = reply;
                        break;
                    }
                    Thread.sleep(25L);
                }
                assertTrue(busyReply != null, "expected backpressure BUSY on saturated shard");
            } finally {
                gateway.service().setShardSlow(shard, false);
            }
            for (int index = 0; index < 70; index++) {
                blocker.readReply();
            }
            long drainDeadline = System.currentTimeMillis() + 5000L;
            while (gateway.service().shard(shard).pendingCount() > 0
                    && System.currentTimeMillis() < drainDeadline) {
                Thread.sleep(20L);
            }
            client.sendCommand("SET", sameShardKey(gateway, shard, "recoveredKey"), "recovered");
            assertEquals("OK", client.readReply().stringValue());
        }
    }

    private int pickShardWithKey(TestGateway gateway, String key) {
        return gateway.service().registry().router().shardIndex(key.getBytes());
    }

    private String sameShardKey(TestGateway gateway, int shard, String suffix) {
        for (int index = 0; index < 100_000; index++) {
            String key = suffix + ":" + index;
            if (gateway.service().registry().router().shardIndex(key.getBytes()) == shard) {
                return key;
            }
        }
        throw new IllegalStateException("no key found for shard " + shard);
    }
}
