# LinkFlow 统计消息重试与死信

## 文档用途

本文说明 LinkFlow 短链接运营平台中访问统计模块的功能边界、消息处理流程和二次开发后的异常处理方式，便于本地运行、代码阅读和面试时介绍项目设计。

## LinkFlow 功能背景

LinkFlow 面向营销推广和渠道投放场景，提供短链接创建、分组管理、有效期控制、访问跳转和数据分析能力。用户访问短链接时，系统需要在完成重定向的同时记录 PV、UV、UIP、访问地区、设备和浏览器等信息。

跳转接口属于高频请求，统计数据保存不能阻塞跳转响应。因此，系统将访问记录先写入 Redis Stream，再由消费者异步落库。管理端通过统计查询接口查看按短链或分组聚合后的数据。

## 功能说明

LinkFlow 的访问统计通过 Redis Stream 异步落库。每条统计消息代表一次短链接访问，包含短链标识、访问时间、访问来源和终端信息。二次开发后，统计消息携带稳定的业务键和重试次数。

- 消费失败且未达到上限时，消息重新写入原统计 Stream。
- 默认最多重试 3 次，可通过 `short-link.stats.stream.max-retries` 配置。
- 达到上限后，消息写入 `short-link:stats-stream:dead-letter`。
- 死信消息保留原始统计数据、重试次数、错误摘要和失败时间。
- 重试或死信写入成功后，原消息从统计 Stream 删除。

正常处理流程如下：

```text
短链跳转请求
  → 生成访问统计记录
  → 写入 Redis Stream
  → Consumer Group 消费消息
  → 更新 PV、UV、UIP 和维度统计
  → 保存访问明细
  → 删除原消息
```

异常处理流程如下：

```text
消费失败
  → 读取 retryCount
  → 未达到上限：重新写入原统计 Stream
  → 达到上限：写入死信 Stream
  → 新消息写入成功后删除原消息
```

## 统计死信查询与重放

管理端右上角账号菜单中的「统计死信」调用 [管理服务接口](../admin/src/main/java/com/nageoffer/shortlink/admin/controller/StatsDeadLetterController.java)。查询和重放都检查 Redis 登录会话和运维用户名名单。未登录或会话无效返回 HTTP 401，已登录但名单中没有该用户名返回 HTTP 403。

| 接口 | 用途 |
| --- | --- |
| `GET /api/short-link/admin/v1/stats/dead-letters` | 按入队顺序倒序查询，每页 20 条，返回死信 ID、重试次数、错误摘要和失败时间。翻页时把响应中的 `data.nextBefore` 作为下一次请求的 `before`。 |
| `POST /api/short-link/admin/v1/stats/dead-letters/replay?id=<死信ID>` | 重放单条死信。HTTP 200 且 `code` 为 `"0"` 时，`data` 是新消息 ID，表示消息已入队。 |

死信 Stream ID 用于定位当前死信，`statsRecord.keys` 用于标识原始访问事件。`statsRecord` 原文携带访问时间 `currentDate`，由消费者继续使用。重放时，Redis Lua 脚本检查原死信是否存在，把原统计数据和重置为 `0` 的 `retryCount` 写入 `short-link:stats-stream`，随后移除原死信。再次提交同一个死信 ID 返回 HTTP 404。原始统计数据缺少有效业务键或必要访问字段时返回 HTTP 422。

[统计消费者](../project/src/main/java/com/nageoffer/shortlink/project/mq/consumer/ShortLinkStatsSaveConsumer.java) 沿用原业务键做短时消费去重，并在一个 MySQL 事务里更新统计和访问明细。普通消费失败会回滚这次写入。上线前遗留的死信可能已经写入部分统计，重放前应先核对原访问的落库情况。这里验证的单次计数范围是**同一条死信通过运维入口重放和重复提交**。

## 本地启动

以聚合模式联调。启动 [聚合服务](../aggregation/README.md)（默认端口 `8003`）、网关（聚合路由，默认端口 `8000`）和 [管理端前端](../console-vue/README.md)（开发模式通常为 `5173`）。前端将 `/api` 代理到网关。聚合服务保留演示模式默认值 `short-link.demo-mode.enable=false`，并在程序参数中设置 `--short-link.domain.default=127.0.0.1:8003`。

1. 在本机 MySQL 建独立测试库，例如 `linkflow_e2e`，并在该库执行 [数据库建表脚本](../resources/database/link.sql)。复制聚合模块的 [ShardingSphere 开发配置](../aggregation/src/main/resources/shardingsphere-config-dev.yaml) 到仓库外的本机目录，把 JDBC URL 指向测试库，填入测试库的本机访问配置和 AES 密钥。聚合服务可通过 `--spring.datasource.url=jdbc:shardingsphere:absolutepath:D:/local/linkflow-e2e.yaml` 加载这份配置。按现有启动方式提供高德地区查询配置 `AMAP_KEY`。
2. 为联调选择一个已确认空闲的 Redis 逻辑库。聚合服务和网关使用相同的 Redis 主机、端口和逻辑库，例如都加 `--spring.data.redis.database=15`。Redis 有密码时按实际配置提供 `REDIS_PASSWORD`。如果本地 Redis 无密码，且聚合服务启动时报 Redis `AUTH` 错误，可使用下面的本机 Redisson 配置，通过 `--spring.redis.redisson.file=file:/D:/local/linkflow-redisson.yaml` 加载，使连接直接使用无密码的测试库。
3. 在 **聚合服务进程**的环境变量中设置 `LINKFLOW_STATS_DEAD_LETTER_OPERATORS=lf_e2e_ops`，多个用户名以英文逗号分隔。改动后重启聚合服务。拆分部署时把名单配置在 `admin`，并确认 `admin`、`project` 和网关使用相同的 Redis 实例及逻辑库。未配置名单时，只有登录态的账号仍返回 HTTP 403。
4. 网关和聚合服务可以使用现有 Nacos 注册发现。单机无 Nacos 时，两个服务都可以加 `--spring.cloud.nacos.discovery.enabled=false` 和 `--spring.cloud.nacos.discovery.register-enabled=false`，并给网关加 `--spring.cloud.discovery.client.simple.instances.short-link-aggregation[0].uri=http://127.0.0.1:8003`，保留真实网关路由及权限校验。

```yaml
singleServerConfig:
  address: "redis://127.0.0.1:6379"
  database: 15
```

## 端到端复现

以下操作只在独立测试库、测试 Redis 逻辑库和专用测试短链上执行。

### 1. 用一次真实访问产生死信

在管理端注册两个测试账号，例如 `lf_member` 和 `lf_e2e_ops`。只把后者添加到运维名单。创建一条测试短链，目标可选 `http://127.00.00.01:5173/` 访问本机管理端首页。记下完整短链标识（例如 `127.0.0.1:8003/<短码>`，去掉协议）。创建成功后，在测试 Redis 库查看 `EXISTS "short-link:goto:127.0.0.1:8003/<短码>"`，应为 `1`。

通过创建短链时的 ShardingSphere 实际 SQL 日志核对分片表，或以完整短链标识在测试库中查出匹配行，记下该链接的 `t_link_goto_*` 和 `t_link_*` 实际表名。确认短链尚无访问，然后在 MySQL 会话中临时改名这一条测试映射。把示例中的 `t_link_goto_N` 换成实际分片表名。

```sql
SET @url = '127.0.0.1:8003/<短码>';
SET @held_url = CONCAT(@url, '-dlq-test');

UPDATE t_link_goto_N SET full_short_url = @held_url WHERE full_short_url = @url;
SELECT ROW_COUNT() AS moved_rows;
```

确认 `moved_rows` 为 `1`，再发出一次请求，例如用 `curl.exe --noproxy "*" -i "http://127.0.0.1:8003/<短码>"`。原跳转缓存仍能返回 HTTP 302，消费者查询测试映射失败。默认配置下，初次消费和 3 次重试都失败后，下面的查询会看到这一条死信。按 `statsRecord.fullShortUrl` 匹配测试短链，记下死信 ID、`statsRecord.keys`、带毫秒的 `currentDate`，并确认 `retryCount` 为 `3`。

```powershell
redis-cli -h 127.0.0.1 -p 6379 -n 15 XREVRANGE short-link:stats-stream:dead-letter + - COUNT 10
```

读取死信后，用原 MySQL 会话恢复测试映射并确认 `restored_rows` 为 `1`。如果中途结束测试，也应执行这一步。

```sql
UPDATE t_link_goto_N SET full_short_url = @url WHERE full_short_url = @held_url;
SELECT ROW_COUNT() AS restored_rows;
```

### 2. 核对普通账号与运维账号权限

普通账号登录后打开「统计死信」，页面应显示「当前账号没有统计死信管理权限」。在浏览器开发者工具的 Network 面板确认查询返回 HTTP 403。要独立验证重放权限，使用普通账号的有效登录 Token 和同一个死信 ID，通过网关直接发送 POST。Token 可从该测试账号登录接口的响应 `data.token` 读取，保存在本地会话变量中。

```powershell
$base = 'http://127.0.0.1:8000/api/short-link/admin/v1/stats/dead-letters'
$deadId = '<本次死信ID>'
$member = 'lf_member'
$memberToken = '<普通账号有效Token>'
curl.exe --noproxy "*" -i -X POST -H "Username: $member" -H "Token: $memberToken" "${base}/replay?id=$deadId"
```

预期返回 HTTP 403，且运维账号随后仍能在列表看到同一个死信 ID。用另外一个浏览器会话登录运维账号打开该列表，确认查询返回 HTTP 200。

### 3. 核对单次计数

运维账号点击重放前，在 MySQL 运行下列查询作为基线。使用同一个会话中的 `@url`，将 `t_link_N` 替换为本测试短链的实际分片表。

```sql
SELECT
    (SELECT total_pv FROM t_link_N WHERE full_short_url = @url) AS total_pv,
    (SELECT COALESCE(SUM(pv), 0) FROM t_link_access_stats WHERE full_short_url = @url) AS access_stats_pv,
    (SELECT COALESCE(SUM(today_pv), 0) FROM t_link_stats_today WHERE full_short_url = @url) AS daily_pv,
    (SELECT COUNT(*) FROM t_link_access_logs WHERE full_short_url = @url) AS access_logs;
```

运维账号在「统计死信」点击该行的「重放」，再点击「确认重放」。从对应 POST 的响应取得新消息 ID。等待消费者完成并重新执行上述 SQL，四项预期都从 `0` 变为 `1`。再用有效运维 Token 和**原死信 ID**发一次 POST，预期 HTTP 404。再次查询 SQL，四项仍为 `1`。期间不要额外访问测试短链。

### 4. 核对原访问时间

使用同一时区把死信原文里的 `currentDate` 转成 MySQL 本地时间，保留毫秒。若 Redis 中是毫秒时间戳，先按联调时区（本次为 Asia/Shanghai）转换。把结果代入下面的 `@original_access_time`，核对 `create_time` 是否等于 MySQL 转为 `DATETIME(0)` 的结果。

```sql
SET @original_access_time = '2026-11-25 19:39:43.958'; -- 换成本次死信的原访问时间

SELECT DATETIME_PRECISION
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 't_link_access_logs'
  AND COLUMN_NAME = 'create_time';

SELECT CAST(@original_access_time AS DATETIME(0)) AS expected_create_time,
       create_time AS actual_create_time,
       update_time AS written_at
FROM t_link_access_logs
WHERE full_short_url = @url;
```

按仓库建表脚本，`create_time` 是秒精度，`DATETIME_PRECISION` 为 `0`。本次 MySQL 实例将 `19:39:43.958` 取整为 `19:39:44`，查询到的 `create_time` 与之相同，重放写入时间 `update_time` 为 `19:39:49`。因此要比较 MySQL 在列精度下保存的原访问时间，避免直接把源时间的毫秒字符串与秒精度列进行比较。

## 本次联调记录

2026-11-25 使用本机 MySQL 8.0、WSL Redis 8 和管理端页面验证。MySQL 使用独立测试库，Redis 使用逻辑库 `15`，Windows 聚合服务经仅监听回环地址的临时转发访问 WSL Redis。

| 检查 | 实测结果 |
| --- | --- |
| 真实访问与重试 | 短链 `1UD25M` 访问一次，重试 3 次后产生死信 `1795606784031-0` |
| 普通账号 | 管理端查询和直接调用重放接口均为 HTTP 403 |
| 运维账号 | 页面查询和确认重放成功，新消息 ID 为 `1795606788474-0` |
| 单次计数 | 总 PV、访问统计 PV、当日 PV、访问明细条数均为 `0 → 1`，重复重放返回 HTTP 404，四项继续为 `1` |
| 访问时间 | 原消息 `19:39:43.958`，MySQL 明细 `19:39:44`，重放写入 `19:39:49` |

联调结束后停止临时联调进程，清理独立测试库和本次写入的 Redis 键。

本地启动修复提交 `336a4c2` 已在干净工作树用 JDK 17 重新验收。短链 `1xL2HK` 产生死信 `1795611599300-0`，运维重放后的新消息为 `1795611602754-0`。普通账号两项操作仍返回 403，重复重放返回 404，四项计数仍为 1。原访问时间 `20:59:59.200` 按列精度保存为 `20:59:59`，重放写入时间为 `21:00:03`。 [本地部署与验收](linkflow-local-deployment.md#10-停止本机实验并记录验证环境)

## 故障排查

- 统计数据没有更新时，先检查 Redis Stream 消费者是否正常运行。
- 反复失败的消息可在 `short-link:stats-stream:dead-letter` 中核对原始内容和错误摘要。
- 调整 `short-link.stats.stream.max-retries` 后，重启运行统计消费者的 `project` 或 `aggregation` 服务。
- 重放入队失败时，可以在死信 Stream 中按原 ID 核对消息是否仍在。重放已入队后仍可能消费失败，可检查统计消费者日志和新产生的死信。

## 策略单元测试

使用 JDK 17 和本机 Maven 执行统计重试策略测试。

```powershell
mvn -pl project -am -Dmaven.compiler.encoding=UTF-8 -Dproject.build.sourceEncoding=UTF-8 test
```

