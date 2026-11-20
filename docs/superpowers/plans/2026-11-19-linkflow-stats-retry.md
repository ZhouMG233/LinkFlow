# LinkFlow 统计可靠性二次开发实施计划

> 本文记录 LinkFlow 短链接运营平台本次二次开发的实施步骤和验证结果。

**Goal:** 将项目展示名统一为 LinkFlow—短链接运营平台，并为 Redis Stream 访问统计消息增加有限重试和死信留存。

## 项目功能背景

LinkFlow 为营销推广和渠道投放提供短链接运营能力，包含短链接创建、批量生成、分组管理、有效期控制、缓存跳转、访问统计和回收站管理。短链访问请求需要快速完成重定向，PV、UV、UIP 以及地区、设备、浏览器等统计数据由 Redis Stream 异步传递给消费者，再落库供管理端查询。

本次二次开发只聚焦统计消息的可靠处理。短链接创建、跳转缓存和统计查询等业务功能继续复用原有实现。

**Architecture:** 在现有 Redis Stream 生产者和消费者之间增加稳定业务键、重试次数和死信 Stream。消费者失败时由同一组件负责重新投递或写入死信，成功消息仍按原流程更新统计数据并删除消息。

**Tech Stack:** Java 17、Spring Boot 3、Spring Data Redis、Redis Stream、JDK 自检程序。

**Spec:** `docs/superpowers/specs/2026-11-19-linkflow-stats-retry-design.md`

## Global Constraints

- 不新增消息中间件。
- 默认最大重试次数为 3，并允许通过 Spring 配置覆盖。
- 重试消息沿用原业务键，不能使用新的业务键绕过幂等。
- 成功消息和已转入死信的原消息都必须从原 Stream 删除。
- 不修改现有统计落库表结构。

## Review Focus

- 统计消息没有 retryCount 时应按首次失败处理。
- retryCount 等于最大次数时应进入死信，不得再次投递。
- 错误信息过长时不能撑大 Stream 消息。
- 重试或死信写入失败时不能误删原消息。
- 旧消息没有业务键时需要兼容并回退到 Stream 记录 ID。

### Task 1: 建立可测试的重试策略

**Files:**
- Create: `project/src/main/java/com/nageoffer/shortlink/project/mq/retry/StatsMessageRetryPolicy.java`
- Test: `project/src/test/java/com/nageoffer/shortlink/project/mq/retry/StatsMessageRetryPolicyTest.java`
- Modify: `project/pom.xml`

- [x] **Step 1: 编写可执行的失败自检**

测试覆盖首次失败、未达到上限时重试、达到上限时死信和非法次数回退为零。

- [x] **Step 2: 运行目标测试确认先失败**

使用 `javac` 编译测试时，预期因为策略类不存在而失败。

- [x] **Step 3: 实现最小策略类**

提供 `retryCount(String)`、`nextAction(int, int)` 和 `maxErrorMessage(String)`，只负责纯计算，不依赖 Spring 或 Redis。

- [x] **Step 4: 运行目标测试确认通过**

使用 `javac` 编译策略类和测试，再运行测试类的 `main` 方法。

### Task 2: 接入生产者和消费者

**Files:**
- Modify: `project/src/main/java/com/nageoffer/shortlink/project/dto/biz/ShortLinkStatsRecordDTO.java`
- Modify: `project/src/main/java/com/nageoffer/shortlink/project/service/impl/ShortLinkServiceImpl.java`
- Modify: `project/src/main/java/com/nageoffer/shortlink/project/mq/consumer/ShortLinkStatsSaveConsumer.java`
- Modify: `project/src/main/java/com/nageoffer/shortlink/project/common/constant/RedisKeyConstant.java`
- Modify: `project/src/main/resources/application.yaml` or the active project configuration file

- [x] **Step 1: 让生产消息生成业务键**

在进入 Stream 前为缺失的 `ShortLinkStatsRecordDTO.keys` 生成 UUID，并把 retryCount 初始为零。

- [x] **Step 2: 以业务键执行幂等判断**

消费者先解析统计记录，再使用业务键判断幂等。旧消息没有业务键时回退到 Stream 记录 ID。

- [x] **Step 3: 添加重试和死信写入**

失败时先写入重试消息或死信消息，写入成功后删除原消息。Redis 操作失败时保留原消息并继续抛出异常。

- [x] **Step 4: 添加配置和常量**

增加最大重试次数配置和死信 Stream 常量，错误摘要限制为固定长度。

### Task 3: 更新展示文档并验证

**Files:**
- Modify: `README.md`
- Create: `docs/linkflow-stats-retry.md`

- [x] **Step 1: 将项目展示名和业务功能统一为 LinkFlow—短链接运营平台**

README、前端模块说明、设计文档和运行说明统一描述短链接创建、访问跳转、访问统计及本次可靠性二次开发。

- [x] **Step 2: 编写运行与验证说明**

记录 Redis Stream、重试配置、死信 Stream 和验证命令。

- [x] **Step 3: 运行完整验证**

运行目标单元测试、`mvnw.cmd -pl project -am test` 和 `git diff --check`。若环境无法运行 Java 或 Maven，记录具体阻塞信息。

- [x] **Step 4: 提交变更**

已完成代码、配置和文档提交，统计重试与死信功能已合并到 LinkFlow 项目版本。

## 完成记录

本次文档同步覆盖根目录说明、管理端前端说明、服务模块说明、数据库脚本说明和统计可靠性设计文档。后续新增模块时，应在模块目录补充对应的 README，避免只依赖根目录概览。

