package com.github.guoyyit.respgatewayjava.command;

public class CommandException extends RuntimeException {

    private final String prefix;

    public CommandException(String prefix, String message) {
        super(message);
        this.prefix = prefix;
    }

    public String getPrefix() { return prefix; }
}
