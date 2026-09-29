package com.github.guoyyit.respgatewayjava.server;

import com.github.guoyyit.respgatewayjava.resp.RespData;

import java.util.TreeMap;
import java.util.function.Consumer;

public class ReplyOrderer {

    private final Consumer<RespData> output;
    private final TreeMap<Long, RespData> pending = new TreeMap<>();
    private long nextSeq = 0;
    private long issued = 0;

    public ReplyOrderer(Consumer<RespData> output) {
        this.output = output;
    }

    public long nextSeq() {
        return issued++;
    }

    public synchronized void complete(long seq, RespData data) {
        pending.put(seq, data);
        while (!pending.isEmpty() && pending.firstKey() == nextSeq) {
            RespData ready = pending.remove(nextSeq);
            nextSeq++;
            output.accept(ready);
        }
    }

    public synchronized int pendingCount() {
        return pending.size();
    }
}
