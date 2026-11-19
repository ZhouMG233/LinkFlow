# LinkFlow—短链接运营平台

LinkFlow 是一个面向营销推广场景的 SaaS 短链接运营平台，提供短链接创建、分组管理、过期控制、访问跳转和访问统计能力。

项目重点围绕高频短链访问场景，使用 Redis 加速跳转请求，使用布隆过滤器和分布式锁控制缓存异常访问，并通过 Redis Stream 将访问统计从跳转主链路中异步处理。

## 核心功能

- 短链接创建、批量创建、分组管理和有效期控制
- 短链接访问跳转和原始链接查询
- PV、UV、UIP、浏览器、设备、操作系统和网络类型统计
- 访问记录查询和分组统计
- 回收站恢复与删除
- 管理端、聚合服务、业务服务和网关模块拆分

## 技术栈

- Java 17
- Spring Boot 3
- Spring Cloud Alibaba
- MySQL、MyBatis-Plus、ShardingSphere
- Redis、Redisson、Redis Stream
- Sentinel、Nacos、OpenFeign、Spring Cloud Gateway
- Vue、Element Plus

## 主要链路

```text
创建长链接
  → 生成 Base62 短码
  → 保存短链映射
  → 写入 Redis 跳转缓存

访问短链接
  → 查询 Redis 缓存
  → 未命中时查询布隆过滤器
  → 使用 Redisson 分布式锁回源数据库
  → 回填缓存并完成跳转

访问统计
  → 生成统计消息
  → 写入 Redis Stream
  → Consumer Group 异步消费
  → 保存 PV、UV、UIP 和访问明细
```

## LinkFlow 二次开发

### Redis Stream 统计消息重试与死信

原有统计消费者在处理异常消息时缺少明确的重试上限和失败消息留存机制。本次二次开发完成了以下改造：

- 为统计消息增加稳定的业务键，重试时沿用业务键完成幂等判断
- 增加 `retryCount` 字段记录当前重试次数
- 消费失败且未达到上限时，重新投递到原统计 Stream
- 默认最多重试 3 次，可通过 `short-link.stats.stream.max-retries` 配置
- 超过重试上限后写入 `short-link:stats-stream:dead-letter` 死信 Stream
- 死信消息保留原始统计数据、错误摘要、失败时间和重试次数
- 重试或死信写入成功后删除原消息，避免异常消息无限堆积

## 配置说明

公开仓库不保存真实密码、用户数据或第三方 API Key。运行项目时通过环境变量提供配置：

- `DB_PASSWORD`  数据库密码
- `DB_AES_KEY`  ShardingSphere 字段加密密钥
- `REDIS_PASSWORD`  Redis 密码
- `AMAP_KEY`  高德地图 API Key

示例数据文件为 `resources/database/link-data.example.sql`，仅用于说明数据格式。

## 本地验证

使用 JDK 17 和 Maven 执行：

```powershell
mvn -pl project -am -Dmaven.compiler.encoding=UTF-8 -Dproject.build.sourceEncoding=UTF-8 test
```

本次二开新增 4 个重试策略测试，覆盖重试判断、死信判断、非法重试次数和错误信息长度限制。

## 项目文档

- [二次开发设计文档](docs/superpowers/specs/2026-11-19-linkflow-stats-retry-design.md)
- [二次开发步骤文档](docs/superpowers/plans/2026-11-19-linkflow-stats-retry.md)
- [重试与死信运行说明](docs/linkflow-stats-retry.md)

