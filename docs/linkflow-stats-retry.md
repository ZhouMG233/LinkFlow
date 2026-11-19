# LinkFlow 统计消息重试与死信

## 功能说明

LinkFlow 的访问统计通过 Redis Stream 异步落库。二次开发后，统计消息携带稳定的业务键和重试次数。

- 消费失败且未达到上限时，消息重新写入原统计 Stream。
- 默认最多重试 3 次，可通过 `short-link.stats.stream.max-retries` 配置。
- 达到上限后，消息写入 `short-link:stats-stream:dead-letter`。
- 死信消息保留原始统计数据、重试次数、错误摘要和失败时间。
- 重试或死信写入成功后，原消息从统计 Stream 删除。

## 本地验证

公开仓库不包含真实种子用户、数据库密码或第三方 API Key。启动本地服务前，请通过环境变量提供 `DB_PASSWORD`、`REDIS_PASSWORD` 和 `AMAP_KEY`。

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
