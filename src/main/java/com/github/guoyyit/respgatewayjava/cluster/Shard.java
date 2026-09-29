package com.github.guoyyit.respgatewayjava.cluster;

import com.github.guoyyit.respgatewayjava.command.Command;
import com.github.guoyyit.respgatewayjava.resp.RespData;

import java.util.concurrent.CompletableFuture;

public interface Shard {

    int index();

    boolean healthy();

    boolean isForcedDown();

    void setHealthDown(boolean down);

    void setSlow(boolean slow);

    void setUnavailable(boolean unavailable);

    java.util.concurrent.CompletableFuture<Void> probe();

    CompletableFuture<RespData> submit(Command command, long topologyEpoch,
                                       java.util.function.LongSupplier currentEpochSupplier,
                                       java.util.function.Supplier<String> policySupplier);

    int pendingCount();
}
