# RESP Gateway (Java)

本地可验证的 RESP 协议网关。客户端通过 TCP 长连接发送 RESP（RESP2）编码命令，
网关完成组帧解析、命令校验后按哈希槽规则路由到多个**本地内存分片**，
并返回协议合规的回复。全部行为均可在本机回环地址上用原始 TCP 字节流验证，
不依赖任何外部服务（无 Redis、无外部中间件）。

## 运行与验证

```bash
mvn spring-boot:run          # 启动网关，默认监听 127.0.0.1:6379
mvn test                     # 运行全部 30 个自动化测试
```

启动后可用 redis-cli 或任意 RESP 客户端连接：

```bash
redis-cli -h 127.0.0.1 -p 6379
```

测试通过回环 socket 直接发送手工构造的 RESP 字节流，覆盖粘包 / 半包、
流水线并发、过期与类型错误、分片故障与恢复、有界缓冲背压以及重启恢复场景。

## 架构

```
客户端 TCP 长连接
      │  （RESP2 字节流，可能粘包/半包）
      ▼
单线程 NIO Reactor（GatewayServer）
  · 增量组帧 RespParser（粘包/半包/超长帧/非法帧）
  · 每连接有界读写缓冲与背压
  · 每连接 ReplyOrderer：严格按命令到达顺序回写
      │  命令分发（CommandDispatcher）
      ▼
ShardManager ── 路由（CRC16/XMODEM 槽位 16384，{tag} hash tag）
      ├── Shard 0  （单线程串行执行 + 有界任务队列）
      ├── Shard 1  （慢/满只影响本分片）
      ├── ...
      └── Shard N
             │
             ▼
       ShardStore（内存键值，惰性过期 + nanoTime 判期）
```

- **顺序性**：同一连接的命令按到达顺序回复。命令可并行落到不同分片执行，
  `ReplyOrderer` 用序号缓冲重排，即使后发命令先完成也不会乱序。
- **并发隔离**：每个分片一个独占工作线程与有界队列；一个分片变慢或队列打满，
  不阻塞其他分片，也不阻塞其他连接的处理。
- **背压有界**：
  - 单次读事件累计读取不超过 `gateway.client-read-buffer-bytes`。
  - 每连接待写出字节不超过 `gateway.client-write-buffer-bytes`；
    达到上限后暂停该连接读取，等内核缓冲排空后自动恢复（内存受控）。
  - 每分片任务队列不超过 `gateway.shard-queue-capacity`；满时新命令收到
    `BUSY` 错误，不会无限堆积；已入队命令继续完成。
- **过期清理不阻塞**：过期判定在读路径惰性完成（`System.nanoTime()`），
  没有全局扫描线程，不会阻塞正常命令。
- **健康检查**：`HealthChecker` 周期性探测分片；故障分片被摘除，期间其命令
  返回 `CLUSTERDOWN`；恢复后自动重新接入，数据不丢失、回复不乱序。

## RESP 协议支持范围

支持 RESP2 组帧：数组 `*`、批量字符串 `$`、简单字符串 `+`、整数 `:`、错误 `-`。

- 解析端：按数组命令 + 非空批量字符串元素解析（与 RESP 内联命令不同，
  与 redis-cli 发送的数组帧一致）。
- 回复端：
  - 简单字符串：`SET`/`MSET`/`PING` 成功 → `+OK\r\n` / `+PONG\r\n`
  - 整数：`DEL`/`EXISTS`/`INCR`/`APPEND`/`EXPIRE`/`TTL`/`PERSIST`
  - 批量字符串：`GET`/`MGET`（二进制安全），空值用 `$-1`（null bulk）
  - 数组：`MGET` 返回数组，缺失元素为 null bulk
  - 错误：`-PREFIX message\r\n`
- 粘包：一个 TCP 段中含多条命令，全部依次解析与回复。
- 半包：一条命令跨多个 TCP 段到达，待字节补齐后解析。
- 超长帧：bulk 声明长度超过 `gateway.max-frame-bytes`，返回协议错误，
  解析器跳过声明字节，后续命令解析状态不被破坏。
- 非法帧：类型符非法 / 长度非整数 / 缺少 CRLF 等，返回协议错误；
  解析器重同步到下一帧边界，同一连接后续命令继续可解析。
- 空字符串值与不存在键可区分：`SET k ""` 后 `GET k` 返回 `$0`，
  不存在键返回 `$-1`；`EXISTS` 据此区分。

## 命令语义

| 命令 | 语义 |
| --- | --- |
| `PING [msg]` | `PONG` 或回显 msg |
| `GET key` | 值（bulk），不存在/已过期为 null |
| `SET key val [EX s] [PX ms]` | 写入；**覆盖写入清除原 TTL**；不支持 NX/XX |
| `DEL key [key...]` | 删除数；多键需同分片 |
| `EXISTS key [key...]` | 存在数（多键需同分片） |
| `EXPIRE key seconds` | 设置相对过期，键存在返回 1 否则 0 |
| `TTL key` / `PTTL key` | 剩余秒 / 毫秒；-1 永久；-2 不存在 |
| `PERSIST key` | 移除过期时间 |
| `INCR key` / `DECR key` | 原子增减；非整数返回 `ERR value is not an integer or out of range` **且不改原值**；溢出报错 |
| `APPEND key value` | 追加，返回新长度；键不存在则新建 |
| `MGET key [key...]` | 值数组；多键必须落同一分片 |
| `MSET k v [k v ...]` | 批量写；键必须落同一分片 |

TTL 行为：

- `SET ... PX/EX` 设置过期；再次 `SET` 覆盖（不带过期选项）清除过期。
- `INCR`/`DECR`/`APPEND` 作用于**已存在且带 TTL 的键时保留剩余过期时间**；
  作用于不存在的键时新建为永久键。
- 过期键在任何时刻读取（`GET/EXISTS/TTL/INCR/APPEND` 等）都视为不存在，
  无论此前是否被访问过。

## 路由与多键约束

- 键空间划分为 16384 个哈希槽，采用 CRC16/XMODEM（与 Redis Cluster 相同的槽算法）。
- 分片索引 = `slot mod shardCount`，分片数由 `gateway.shard-count` 配置。
- 支持 hash tag：键中 `{...}` 内的子串参与哈希，便于把多键命令固定到同一分片，
  例如 `MGET {user:1}name {user:1}email`。
- 多键命令（`DEL/EXISTS/MGET/MSET`）的所有键必须落在同一分片，否则返回
  `CROSSSLOT keys in request hash to different shards`。

## 拓扑变更策略

- `ShardManager` 维护单调递增的 epoch。命令在**分片任务真正执行时**校验 epoch。
- 分片数变更（`GatewayAdmin.reshard(n)`）会：提升 epoch → 重建分片 →
  以拓扑变更错误完成旧分片上已接收但未完成的任务 → 持久化新拓扑。
- 因此拓扑变更前已接收、变更时仍未完成的命令收到确定的错误
  `ERR cluster topology changed, command rejected`；变更后新命令按新拓扑执行，
  不会出现乱序或悬挂回复。

## 错误语义

| 错误前缀 | 触发场景 |
| --- | --- |
| `ERR` | 参数个数错误、值非整数、未知命令、协议错误、拓扑变更、内部错误 |
| `CROSSSLOT` | 多键命令的键分布在不同分片 |
| `CLUSTERDOWN` | 目标分片故障已被摘除 |
| `BUSY` | 分片任务队列已满（背压拒绝） |

未知命令统一返回 `ERR unknown command '<name>'`，连接与解析状态保持可用。

## 持久化与重启

- 配置与路由表（分片数、epoch）写入 `data/gateway.meta`；
  每个分片的数据写入 `data/shard-<id>.dat`（Base64，原子临时文件 + rename）。
- TTL 以 wall-clock 毫秒持久化，重启后剩余过期时间语义保持一致；
  重启时已过期的键不恢复。
- 重启前未完成的命令不恢复（按失败处理），客户端连接被明确关闭。
- 默认在 Spring 正常关闭与 JVM shutdown hook 时保存快照。

## 配置项（`application.properties`，前缀 `gateway`）

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `port` | 6379 | 监听端口（绑定 127.0.0.1） |
| `shard-count` | 4 | 分片数量 |
| `shard-queue-capacity` | 1024 | 每分片有界任务队列长度，满则 BUSY |
| `client-read-buffer-bytes` | 1048576 | 单连接单次读事件读取上限 |
| `client-write-buffer-bytes` | 1048576 | 单连接待写出字节上限，达限暂停读取 |
| `idle-timeout-ms` | 60000 | 连接空闲超时回收（半开/死连接清理，配合 TCP keepalive） |
| `max-frame-bytes` | 67108864 | 单帧最大字节数，超过返回协议错误 |
| `health-check-interval-ms` | 1000 | 分片健康探测周期 |
| `health-check-timeout-ms` | 200 | 健康探测超时 |
| `data-dir` | data | 配置/路由表/数据持久化目录 |

## 测试覆盖

- `FrameParsingTest`：全回复类型、粘包、半包、非法帧恢复、超长帧、空串 vs 缺键、MGET、二进制安全。
- `CommandSemanticsTest`：覆盖写清 TTL、INCR 类型错误不改值、INCR/APPEND 保留 TTL、
  DEL/EXISTS/EXPIRE/TTL/PERSIST、过期不可见、MSET/MGET 同片、CROSSSLOT、未知命令、参数个数。
- `ConcurrencyTest`：流水线 200 命令按序、8 客户端并发互不阻塞、慢分片不阻塞其他分片/连接。
- `ShardHealthTest`：故障摘除返回 CLUSTERDOWN 且其他分片可用、恢复自动接入数据保留；
  队列打满返回 BUSY、恢复后命令继续完成。
- `PersistenceRestartTest`：数据/分片数/TTL 重启恢复、过期键不恢复、拓扑变更拒绝旧 epoch 命令。
- `ConnectionLifecycleTest`：空闲连接回收、活跃连接不误关、停机时显式关闭客户端连接。
