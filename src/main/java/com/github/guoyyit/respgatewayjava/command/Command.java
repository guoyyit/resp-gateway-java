package com.github.guoyyit.respgatewayjava.command;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class Command {

    private final String name;
    private final List<byte[]> args;

    public Command(String name, List<byte[]> args) {
        this.name = name;
        this.args = args;
    }

    public String getName() { return name; }
    public List<byte[]> getArgs() { return args; }

    public String argString(int index) {
        return new String(args.get(index), StandardCharsets.UTF_8);
    }
}
