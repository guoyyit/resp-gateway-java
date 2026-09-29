package com.github.guoyyit.respgatewayjava.command;

public enum CommandName {
    GET,
    SET,
    DEL,
    EXISTS,
    EXPIRE,
    TTL,
    PERSIST,
    INCR,
    APPEND,
    MGET,
    MSET,
    PING,
    UNKNOWN;

    public static CommandName fromString(String raw) {
        if (raw == null || raw.isEmpty()) {
            return UNKNOWN;
        }
        String normalized = raw.toUpperCase(java.util.Locale.ROOT);
        for (CommandName name : values()) {
            if (name != UNKNOWN && name.name().equals(normalized)) {
                return name;
            }
        }
        return UNKNOWN;
    }
}
