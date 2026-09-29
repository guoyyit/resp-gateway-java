# RESP Gateway (Java)

本地可验证的 RESP（RESP2）协议网关。客户端通过 TCP 长连接发送 RESP 编码命令，
网关完成组帧、校验、按键分片路由，把命令分派到多个**本地后端分片**执行，并返回
协议合规的回复。后端分片为网关进程内的本地存储执行体，验证过程不依赖任何外部服务。

## 运行

```bash
mvn spring-boot:run
# RESP 服务默认监听 127.0.0.1:6390
```

用任意 RESP/Redis 客户端连接回环地址即可，例如：

```bash
redis-cli -h 127.0.0.1 -p 6390 SET k v
redis-cli -h 127.0.0.1 -p 6390 GET k
```

## 测试

```bash
mvn test
```

测试全部在本地回环上以原始字节流驱动，覆盖：粘包/半包与超长帧恢复、流水线并发与保序、
过期与类型错误、多连接与单分片变慢隔离、跨片错误、分片故障摘除与自动恢复、
背压 BUSY、空闲/半开连接回收、以及重启后拓扑/配置恢复与连接关闭。

## 协议支持范围（RESP2）

- 入站组帧：数组（`*n`）、批量字符串（`$n`）、简单字符串（`+`）、整数（`:`）、错误（`-`）。
- 命令必须是非空数组，命令名与参数为批量字符串（Redis 客户端的标准编码）。
- 出站回复：简单字符串、错误、整数、批量字符串（含 `$-1` 空值）、数组（含空数组元素）。
- 正确处理粘包（一个 TCP 段多帧）与半包（一帧跨多个 TCP 段）。
- 单帧超过 `resp.gateway.max-frame-bytes` 时返回协议错误，并**跳过该帧的全部字节**，
  不破坏同连接后续命令的解析状态；非法类型/编码同样返回协议错误后继续解析后续帧。

## 命令

| 命令 | 语义 |
| --- | --- |
| `GET key` | 不存在/已过期返回 `$-1`；空字符串值返回 `$0`，二者可区分 |
| `SET key value [EX s] [PX ms]` | 覆盖写入并**清除原有过期时间**（除非显式给定新 TTL） |
| `DEL key [key ...]` | 返回实际删除数量 |
| `EXISTS key [key ...]` | 返回存在且未过期的键数量（重复键按重复计数） |
| `EXPIRE key seconds` | 设置相对过期时间；键不存在返回 `0` |
| `TTL key` | 剩余秒数；无过期 `-1`；不存在/已过期 `-2` |
| `PERSIST key` | 移除过期时间 |
| `INCR key` | 整数自增；对非整数返回 `WRONGTYPE` 且**不修改原值**；保留剩余 TTL |
| `APPEND key value` | 追加，返回新长度；保留剩余 TTL |
| `MGET key [key ...]` | 与键顺序对齐的批量数组，缺失位置为 `$-1` |
| `MSET k v [k v ...]` | 成组写入（清除 TTL） |
| `PING` | 返回 `+PONG` |
| 其他命令 | 返回确定的 `ERR unknown command ...` |

键的过期在任何时刻读取都按过期处理（惰性判定），并有独立于命令路径的后台清理线程，
清理不会阻塞正常命令执行。

## 分片与路由

- 分片数量可配置（`resp.gateway.shard-count`）。
- 路由规则：键字节做 CRC16（MODBUS 变体）哈希后对分片数取模；同一拓扑下结果确定稳定。
- 多键命令（`MGET`/`MSET`/`DEL`/`EXISTS`）必须全部落在同一分片，否则返回
  `CROSSSLOT keys in request hash to different slots`，命令不执行。
- 每个分片由独立单线程执行器串行推进，命令执行按分片隔离：单个分片变慢只会拖慢
  发往该分片的命令，其他分片与其他连接不受阻塞。

### 拓扑变更策略

- `REJECT`（默认）：拓扑 epoch 递增后，变更前已接收、变更后才执行的在途命令返回
  `CLUSTERDOWN topology changed while command was in flight`；新 epoch 命令正常执行。
- `DRAIN`：缩小分片数前先等待待摘除分片的在途命令排空，再完成切换。

## 故障、健康检查与背压

- 健康检查按固定周期对每个分片探活：探活失败/超时则摘除，期间该分片命令立即返回
  `CLUSTERDOWN shard N is unavailable`；探活恢复后自动重新接入。
- 人工摘除（管理动作）不会被自动探活覆盖，直到显式恢复。
- 摘除与恢复都不改变命令在单连接上的顺序：回复由每连接的序列号保序写出，不丢失、不乱序。
- 每分片待执行队列有界（`shard-queue-capacity`）：打满后新命令返回
  `BUSY shard N is saturated, backpressure engaged`，不做无界排队。
- 每连接读取缓冲与未回复命令数有界（`connection-read-buffer-bytes`、
  `max-inflight-per-connection`）：达到在途上限后暂停从该连接读取（TCP 背压反压客户端），
  分片恢复后未拒绝的命令继续完成，全程内存占用受控。

## 连接管理

- 同一连接上的命令严格按到达顺序执行并按相同顺序回复；允许客户端在未收到前一回复时
  继续发送（流水线）。
- 多连接由独立工作线程并发推进，互不阻塞。
- 空闲超过 `idle-timeout` 的连接被回收；半包数据在 `half-open-timeout` 内没有继续到达的
  半开连接被清理。网关关闭时所有客户端连接被显式关闭。

## 错误回复前缀

- `ERR`：通用协议/参数/未知命令/内部错误。
- `WRONGTYPE`：对非整数值执行 `INCR`。
- `CROSSSLOT`：多键命令跨分片。
- `CLUSTERDOWN`：分片不可用、在途命令遭遇拓扑变更。
- `BUSY`：分片队列饱和触发背压。

## 持久化与重启

- 配置与路由表（分片数量、epoch、拓扑策略）以 JSON 原子写入 `data/` 目录
  （`gateway-config.json`、`topology.json`）。
- 重启后恢复一致的分片拓扑与 epoch。后端数据为进程内内存态（本任务持久化范围限定为
  配置与路由表）；重启前未完成的命令按失败处理，客户端连接被明确关闭。

## 配置项（`application.properties`，前缀 `resp.gateway`）

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `port` | `6390` | RESP TCP 端口（绑定 127.0.0.1） |
| `shard-count` | `4` | 分片数（首次启动；之后由持久化拓扑恢复） |
| `shard-queue-capacity` | `256` | 每分片在途命令队列上限 |
| `max-frame-bytes` | `67108864` | 单帧最大字节数 |
| `connection-read-buffer-bytes` | `1048576` | 每连接读缓冲上限 |
| `max-inflight-per-connection` | `256` | 每连接未回复命令上限 |
| `idle-timeout` | `5m` | 空闲连接回收时间 |
| `half-open-timeout` | `2m` | 半包停滞清理时间 |
| `health-check-interval` | `2s` | 分片健康检查周期 |
| `topology-change-policy` | `REJECT` | 拓扑变更策略（`REJECT`/`DRAIN`） |
| `data-dir` | `data` | 配置与路由表持久化目录 |
