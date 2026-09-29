package com.github.guoyyit.respgatewayjava.command;

import com.github.guoyyit.respgatewayjava.resp.RespData;

import java.util.List;

public final class Command {

    private final CommandName name;
    private final List<RespData> args;

    public Command(CommandName name, List<RespData> args) {
        this.name = name;
        this.args = List.copyOf(args);
    }

    public CommandName name() {
        return name;
    }

    public List<RespData> args() {
        return args;
    }

    public RespData arg(int index) {
        return args.get(index);
    }

    public int arity() {
        return args.size();
    }
}
