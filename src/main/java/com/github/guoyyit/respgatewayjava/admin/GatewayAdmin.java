package com.github.guoyyit.respgatewayjava.admin;

import com.github.guoyyit.respgatewayjava.persist.PersistenceService;
import com.github.guoyyit.respgatewayjava.shard.Shard;
import com.github.guoyyit.respgatewayjava.shard.ShardManager;

import java.util.List;

public class GatewayAdmin {

    private final ShardManager shardManager;
    private final PersistenceService persistenceService;

    public GatewayAdmin(ShardManager shardManager, PersistenceService persistenceService) {
        this.shardManager = shardManager;
        this.persistenceService = persistenceService;
    }

    public void setShardFault(int shardId, boolean fault) {
        List<Shard> shards = shardManager.getShards();
        if (shardId < 0 || shardId >= shards.size()) {
            throw new IllegalArgumentException("invalid shard id: " + shardId);
        }
        shards.get(shardId).setFault(fault);
    }

    public void reshard(int newShardCount) {
        if (newShardCount <= 0) {
            throw new IllegalArgumentException("shard count must be positive");
        }
        if (newShardCount == shardManager.getShardCount()) {
            return;
        }
        shardManager.replaceShards(newShardCount);
        persistenceService.save(shardManager);
    }

    public void save() {
        persistenceService.save(shardManager);
    }
}
