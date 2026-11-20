# LinkFlow 短链接核心服务

`project` 是 LinkFlow 的核心业务模块，负责短链接生命周期、访问跳转和访问数据统计。

## 功能说明

- 根据长 URL 生成 Base62 短码，支持单条和批量创建
- 管理短链接目标地址、分组、有效期和回收站状态
- 使用 Redis、布隆过滤器和 Redisson 分布式锁优化高频跳转
- 记录 PV、UV、UIP、地区、设备、浏览器和网络等访问维度
- 使用 Redis Stream 异步消费统计消息并落库
- 对统计消费失败消息执行有限重试，超过上限后写入死信 Stream

该模块通过 MyBatis-Plus 访问 MySQL，通过 Redis 保存跳转缓存和异步统计消息。

