package com.github.guoyyit.respgatewayjava.command;

import com.github.guoyyit.respgatewayjava.resp.RespData;
import com.github.guoyyit.respgatewayjava.storage.ShardStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class CommandHandler {

    private CommandHandler() {
    }

    public static RespData execute(Command command, ShardStore store) {
        return switch (command.name()) {
            case PING -> RespData.simpleString("PONG");
            case GET -> get(command, store);
            case SET -> set(command, store);
            case DEL -> del(command, store);
            case EXISTS -> exists(command, store);
            case EXPIRE -> expire(command, store);
            case TTL -> ttl(command, store);
            case PERSIST -> persist(command, store);
            case INCR -> incr(command, store);
            case APPEND -> append(command, store);
            case MGET -> mget(command, store);
            case MSET -> mset(command, store);
            case UNKNOWN -> unknown(command);
        };
    }

    public static List<byte[]> keys(Command command) {
        List<RespData> args = command.args();
        return switch (command.name()) {
            case PING -> List.of();
            case MGET -> bulkKeys(args, 0, args.size());
            case MSET -> {
                List<byte[]> keys = new ArrayList<>();
                for (int index = 0; index + 1 < args.size(); index += 2) {
                    keys.add(asBytes(args.get(index)));
                }
                yield keys;
            }
            default -> args.isEmpty() ? List.of() : List.of(asBytes(args.get(0)));
        };
    }

    private static RespData get(Command command, ShardStore store) {
        requireArity(command, 1, "GET");
        byte[] value = store.get(asBytes(command.arg(0)));
        if (value == null) {
            return RespData.nullBulk();
        }
        return RespData.bulk(value);
    }

    private static RespData set(Command command, ShardStore store) {
        if (command.arity() < 2) {
            throw new GatewayError("ERR wrong number of arguments for 'set' command");
        }
        long ttlMillis = parseSetOptions(command);
        store.set(asBytes(command.arg(0)), asBytes(command.arg(1)), ttlMillis);
        return RespData.simpleString("OK");
    }

    private static long parseSetOptions(Command command) {
        Long explicitMillis = null;
        Long explicitSeconds = null;
        for (int index = 2; index < command.arity(); index++) {
            String option = asText(command.arg(index)).toUpperCase(java.util.Locale.ROOT);
            switch (option) {
                case "PX" -> {
                    long value = readOptionValue(command, index, "PX");
                    explicitMillis = value;
                    index++;
                }
                case "EX" -> {
                    long value = readOptionValue(command, index, "EX");
                    explicitSeconds = value;
                    index++;
                }
                case "KEEPTTL" -> {
                    // 本网关 SET 语义为覆盖写入并清除过期时间，KEEPTTL 不被支持以保持确定语义。
                    throw new GatewayError("ERR unsupported SET option KEEPTTL");
                }
                default -> throw new GatewayError("ERR syntax error near '" + option + "'");
            }
        }
        if (explicitMillis != null && explicitSeconds != null) {
            throw new GatewayError("ERR syntax error");
        }
        if (explicitMillis != null) {
            if (explicitMillis <= 0L) {
                throw new GatewayError("ERR invalid expire time in 'set' command");
            }
            return explicitMillis;
        }
        if (explicitSeconds != null) {
            if (explicitSeconds <= 0L) {
                throw new GatewayError("ERR invalid expire time in 'set' command");
            }
            return explicitSeconds * 1000L;
        }
        return 0L;
    }

    private static long readOptionValue(Command command, int optionIndex, String option) {
        if (optionIndex + 1 >= command.arity()) {
            throw new GatewayError("ERR syntax error near '" + option + "'");
        }
        Long value = parseLongText(asText(command.arg(optionIndex + 1)));
        if (value == null) {
            throw new GatewayError("ERR value is not an integer or out of range");
        }
        return value;
    }

    private static RespData del(Command command, ShardStore store) {
        if (command.arity() < 1) {
            throw new GatewayError("ERR wrong number of arguments for 'del' command");
        }
        return RespData.integer(store.del(extractKeys(command)));
    }

    private static RespData exists(Command command, ShardStore store) {
        if (command.arity() < 1) {
            throw new GatewayError("ERR wrong number of arguments for 'exists' command");
        }
        return RespData.integer(store.exists(extractKeys(command)));
    }

    private static RespData expire(Command command, ShardStore store) {
        requireArity(command, 2, "EXPIRE");
        Long seconds = parseLongText(asText(command.arg(1)));
        if (seconds == null) {
            throw new GatewayError("ERR value is not an integer or out of range");
        }
        return RespData.integer(store.expire(asBytes(command.arg(0)), seconds * 1000L));
    }

    private static RespData ttl(Command command, ShardStore store) {
        requireArity(command, 1, "TTL");
        return RespData.integer(store.ttl(asBytes(command.arg(0))));
    }

    private static RespData persist(Command command, ShardStore store) {
        requireArity(command, 1, "PERSIST");
        return RespData.integer(store.persist(asBytes(command.arg(0))));
    }

    private static RespData incr(Command command, ShardStore store) {
        requireArity(command, 1, "INCR");
        try {
            return RespData.integer(store.incr(asBytes(command.arg(0)), 1L));
        } catch (ShardStore.WrongTypeException exception) {
            throw GatewayError.wrongType(exception.getMessage());
        } catch (ArithmeticException exception) {
            throw new GatewayError("ERR increment or decrement would overflow");
        }
    }

    private static RespData append(Command command, ShardStore store) {
        requireArity(command, 2, "APPEND");
        return RespData.integer(store.append(asBytes(command.arg(0)), asBytes(command.arg(1))));
    }

    private static RespData mget(Command command, ShardStore store) {
        if (command.arity() < 1) {
            throw new GatewayError("ERR wrong number of arguments for 'mget' command");
        }
        List<RespData> replies = new ArrayList<>(command.arity());
        for (RespData argument : command.args()) {
            byte[] value = store.get(asBytes(argument));
            replies.add(value == null ? RespData.nullBulk() : RespData.bulk(value));
        }
        return RespData.array(replies);
    }

    private static RespData mset(Command command, ShardStore store) {
        if (command.arity() < 2 || command.arity() % 2 != 0) {
            throw new GatewayError("ERR wrong number of arguments for 'mset' command");
        }
        for (int index = 0; index < command.arity(); index += 2) {
            store.set(asBytes(command.arg(index)), asBytes(command.arg(index + 1)), 0L);
        }
        return RespData.simpleString("OK");
    }

    private static RespData unknown(Command command) {
        throw new GatewayError("ERR unknown command '"
                + command.name() + "', with args beginning at #0");
    }

    private static byte[][] extractKeys(Command command) {
        byte[][] keys = new byte[command.arity()][];
        for (int index = 0; index < command.arity(); index++) {
            keys[index] = asBytes(command.arg(index));
        }
        return keys;
    }

    private static List<byte[]> bulkKeys(List<RespData> arguments, int from, int to) {
        List<byte[]> keys = new ArrayList<>(to - from);
        for (int index = from; index < to; index++) {
            keys.add(asBytes(arguments.get(index)));
        }
        return keys;
    }

    private static void requireArity(Command command, int expected, String commandName) {
        if (command.arity() != expected) {
            throw new GatewayError("ERR wrong number of arguments for '"
                    + commandName.toLowerCase(java.util.Locale.ROOT) + "' command");
        }
    }

    private static byte[] asBytes(RespData data) {
        if (data.type() != com.github.guoyyit.respgatewayjava.resp.RespType.BULK_STRING
                || data.isNull()) {
            throw new GatewayError("ERR protocol error: expected bulk string argument");
        }
        return data.bulkBytes().clone();
    }

    private static String asText(RespData data) {
        return new String(asBytes(data), StandardCharsets.UTF_8);
    }

    private static Long parseLongText(String text) {
        if (text.isEmpty() || text.length() > 20) {
            return null;
        }
        int begin = 0;
        if (text.charAt(0) == '-' || text.charAt(0) == '+') {
            if (text.length() == 1) {
                return null;
            }
            begin = 1;
        }
        long value = 0L;
        for (int index = begin; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character < '0' || character > '9') {
                return null;
            }
            value = value * 10 + (character - '0');
        }
        if (text.charAt(0) == '-') {
            return value == Long.MIN_VALUE ? Long.MIN_VALUE : -value;
        }
        return value;
    }
}
