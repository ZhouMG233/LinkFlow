# LinkFlow—短链接运营平台

LinkFlow 是一个面向营销推广场景的 SaaS 短链接运营平台。系统将长 URL 转换为短码，并围绕短链创建、访问跳转、用户分组和访问数据分析提供完整的运营能力。

项目重点解决短链服务中的热点访问、缓存异常、统计链路阻塞、数据分片和服务拆分问题，适合作为 Java 后端学习和二次开发项目。

本项目基于原项目进行二次开发，借助 Vibe Coding 辅助完成代码阅读、方案梳理、功能实现、测试验证和文档整理，关键设计与代码经过人工复核。

## 项目定位

短链接服务的基本流程如下：

1. 用户提交一条长 URL。
2. 系统生成唯一短码，并保存短码与原始 URL 的映射关系。
3. 用户访问短链接时，系统根据短码查询原始 URL。
4. 服务端返回重定向响应，将用户带到目标页面。
5. 系统异步记录访问时间、访问用户、IP、设备和浏览器等统计信息。

短链接可以用于营销短信、活动海报、内容推广和渠道投放。运营人员可以通过不同分组和统计数据了解链接传播效果。

## 核心功能

### 短链接管理

- 创建短链接和批量创建短链接
- 基于 Base62 短码生成访问地址
- 支持自定义分组和有效期控制
- 支持短链接分页查询、修改和删除
- 支持回收站恢复和彻底删除

### 访问跳转

- 根据短码查询原始 URL
- 优先从 Redis 获取热点短链
- 缓存未命中时回源 MySQL
- 对不存在的短码进行空值缓存
- 使用布隆过滤器减少无效请求对数据库的访问
- 使用 Redisson 分布式锁控制并发回源

### 访问统计

- 统计 PV、UV 和 UIP
- 记录访问日期、小时和星期信息
- 统计访问地区、操作系统、浏览器、设备和网络类型
- 保存访问明细并支持按短链或分组查询
- 使用 Redis Set 对 Cookie 和 IP 进行去重

## 系统结构

```text
console-vue
    ↓
gateway
    ↓
admin ───── aggregation
              ↓
           project
              ↓
       MySQL / Redis / Nacos
```

| 模块 | 职责 |
| --- | --- |
| `console-vue` | 管理端前端页面和接口交互 |
| `gateway` | 统一入口、请求路由、Token 校验和流量控制 |
| `admin` | 用户、分组、回收站和管理端业务 |
| `aggregation` | 聚合管理端与业务服务，提供统一服务入口 |
| `project` | 短链创建、访问跳转和访问统计核心业务 |
| `resources/database` | 数据库表结构和公开示例数据说明 |

## 技术栈

- Java 17
- Spring Boot 3
- Spring Cloud Alibaba
- Spring Cloud Gateway
- Nacos
- OpenFeign
- MySQL
- MyBatis-Plus
- ShardingSphere
- Redis
- Redisson
- Redis Stream
- Sentinel
- Vue、Element Plus

## 关键设计

### 短码生成

短链接创建时，系统基于 URL 内容生成 Hash 结果，再转换为 Base62 短码。写入数据库时使用唯一约束处理重复写入，并将短链映射加入 Redis 缓存和布隆过滤器。

### 缓存与跳转

访问短链接时，系统先查询 Redis 跳转缓存。缓存命中后直接完成重定向。缓存未命中时，先通过布隆过滤器判断短码是否可能存在，再使用 Redisson 分布式锁控制数据库回源。获得锁后再次检查缓存，避免多个请求同时查询数据库。查询不到的短码会写入空值缓存，降低缓存穿透风险。

```text
访问短链
  → 查询 Redis
  → 命中：直接 302 跳转
  → 未命中：查询布隆过滤器
  → 不存在：写入空值缓存并结束
  → 可能存在：获取 Redisson 锁
  → 二次检查 Redis
  → 查询 MySQL
  → 回填缓存并完成跳转
```

### 访问统计异步化

短链跳转请求需要尽快返回，统计保存、访问地区解析和访问明细写入属于后置处理。系统将统计记录写入 Redis Stream，由 Consumer Group 异步消费并保存到数据库，减少统计逻辑对跳转接口响应时间的影响。

```text
跳转请求
  → 生成统计记录
  → 写入 Redis Stream
  → Consumer Group 拉取消息
  → 保存 PV、UV、UIP 和访问明细
  → 删除已处理消息
```

## LinkFlow 二次开发

### Redis Stream 统计消息重试与死信

原有统计消费者在发生异常时缺少明确的重试上限和失败消息留存机制。本次二次开发围绕消息可靠性完成以下改造：

- 为统计消息增加稳定的业务键，重试消息沿用原业务键
- 使用业务键参与幂等判断，减少重试过程中的重复处理
- 增加 `retryCount` 字段记录当前重试次数
- 消费失败且未达到上限时，重新投递到原统计 Stream
- 默认最多重试 3 次，可通过 `short-link.stats.stream.max-retries` 配置
- 超过重试上限后写入 `short-link:stats-stream:dead-letter` 死信 Stream
- 死信消息保留原始统计内容、错误摘要、失败时间和重试次数
- 重试或死信写入成功后删除原消息，避免异常消息无限堆积

### 统计死信运维入口

在 `admin`（独立部署）或 `aggregation`（聚合部署）设置 `LINKFLOW_STATS_DEAD_LETTER_OPERATORS`，填写已有运维账号的用户名，多个账号用英文逗号分隔。默认关闭权限。运维账号登录后，从管理端右上角账号菜单进入「统计死信」。

- `GET /api/short-link/admin/v1/stats/dead-letters` 按入队顺序倒序查询，每页 20 条。继续查询时将返回的 `nextBefore` 传给 `before`。列表显示死信 ID、重试次数、错误摘要和失败时间。
- `POST /api/short-link/admin/v1/stats/dead-letters/replay?id=<死信ID>` 将一条死信重新投入统计 Stream。成功返回新消息 ID，表示已入队、等待消费。原访问数据和业务键沿用，重试计数重新从零开始。
- 运维入口与统计消费者使用同一个 Redis 实例。重放在 Redis 内原子执行入队和移除，两步均成功后旧死信才从列表移除。同一死信再次重放会返回 404。部署本次修改前生成的死信可能已有部分统计落库，重放前应先核对。

本地启动参数、权限验证、重放前后 SQL 对照和访问时间核对步骤见 [统计死信端到端复现](docs/linkflow-stats-retry.md#端到端复现)。

### 二次开发验证

新增 `StatsMessageRetryPolicyTest`，覆盖以下场景：

- 当前重试次数未达到上限时继续重试
- 当前重试次数达到上限时进入死信
- 空值或非法重试次数回退为零
- 错误信息长度限制

## 配置说明

公开仓库不保存真实用户数据、数据库密码、Redis 密码、字段加密密钥或第三方 API Key。运行项目时通过环境变量注入配置：

| 环境变量 | 用途 |
| --- | --- |
| `DB_PASSWORD` | MySQL 数据库密码 |
| `DB_AES_KEY` | ShardingSphere AES 字段加密密钥 |
| `REDIS_PASSWORD` | Redis 密码 |
| `AMAP_KEY` | 高德地图 API Key |
| `LINKFLOW_STATS_DEAD_LETTER_OPERATORS` | 统计死信运维用户名，多个用户名用英文逗号分隔 |

`resources/database/link.sql` 只包含数据库结构。`resources/database/link-data.example.sql` 仅用于说明公开示例数据格式，不包含真实用户信息。

## 本地运行准备

1. 安装 JDK 17、Maven、MySQL、Redis 和 Nacos。
2. 创建 `link` 数据库并执行 `resources/database/link.sql`。
3. 设置数据库、Redis 和高德地图相关环境变量。
4. 根据本地服务地址检查各模块的 `application.yaml`。
5. 启动 MySQL、Redis 和 Nacos。聚合模式启动 `aggregation` 和 `gateway`。拆分模式启动 `admin`、`project` 和 `gateway`，并将网关切换到 `dev` 配置。
6. 启动 `console-vue` 后访问管理端页面。

## 构建与测试

使用 JDK 17 和 Maven 执行项目模块测试：

```powershell
mvn -pl project -am -Dmaven.compiler.encoding=UTF-8 -Dproject.build.sourceEncoding=UTF-8 test
```

本次二次开发新增 4 个重试策略测试，当前测试结果为 4 个测试通过、0 个失败。

## 项目文档

- [管理端前端功能说明](console-vue/README.md)
- [管理服务说明](admin/README.md)
- [聚合服务说明](aggregation/README.md)
- [网关服务说明](gateway/README.md)
- [短链接核心服务说明](project/README.md)
- [数据库脚本说明](resources/database/README.md)
- [二次开发设计文档](docs/superpowers/specs/2026-11-19-linkflow-stats-retry-design.md)
- [二次开发步骤文档](docs/superpowers/plans/2026-11-19-linkflow-stats-retry.md)
- [重试与死信运行说明](docs/linkflow-stats-retry.md)

## 原项目地址

- [nageoffer/shortlink](https://github.com/nageoffer/shortlink)

