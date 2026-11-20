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

## 本地验证

公开仓库不包含真实种子用户、数据库密码或第三方 API Key。启动本地服务前，请通过环境变量提供 `DB_PASSWORD`、`DB_AES_KEY`、`REDIS_PASSWORD` 和 `AMAP_KEY`。

使用 JDK 17 或项目兼容的 JDK 运行 Maven 测试。当前仓库未提交 Maven Wrapper 缓存时，可使用本机 Maven 执行：

```powershell
mvn -pl project -DskipTests compile
mvn -pl project test
```

纯策略自检可以直接运行：

```powershell
javac -d tmp/retry-test project/src/main/java/com/nageoffer/shortlink/project/mq/retry/StatsMessageRetryPolicy.java project/src/test/java/com/nageoffer/shortlink/project/mq/retry/StatsMessageRetryPolicyTest.java
java -cp tmp/retry-test com.nageoffer.shortlink.project.mq.retry.StatsMessageRetryPolicyTest
```

