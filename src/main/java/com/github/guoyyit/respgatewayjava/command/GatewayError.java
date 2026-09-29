package com.github.guoyyit.respgatewayjava.command;

import com.github.guoyyit.respgatewayjava.resp.RespData;

public final class GatewayError extends RuntimeException {

    private final String prefix;

    public GatewayError(String message) {
        this("ERR", message);
    }

    public GatewayError(String prefix, String message) {
        super(prefix + " " + message);
        this.prefix = prefix;
    }

    public static GatewayError wrongType(String message) {
        return new GatewayError("WRONGTYPE", message);
    }

    public static GatewayError crossSlot(String message) {
        return new GatewayError("CROSSSLOT", message);
    }

    public static GatewayError clusterDown(String message) {
        return new GatewayError("CLUSTERDOWN", message);
    }

    public static GatewayError busy(String message) {
        return new GatewayError("BUSY", message);
    }

    public String prefix() {
        return prefix;
    }

    public RespData toReply() {
        return RespData.error(getMessage());
    }
}
