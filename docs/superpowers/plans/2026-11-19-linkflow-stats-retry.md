# LinkFlow Stats Retry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将项目展示名统一为 LinkFlow—短链接运营平台，并为 Redis Stream 统计消息增加有限重试和死信留存。

**Architecture:** 在现有 Redis Stream 生产者和消费者之间增加稳定业务键、重试次数和死信 Stream。消费者失败时由同一组件负责重新投递或写入死信，现有统计落库逻辑保持不变。

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

- [x] **Step 1: 将项目展示名更新为 LinkFlow—短链接运营平台**

README 的项目标题、简介和新增功能说明使用统一名称。

- [x] **Step 2: 编写运行与验证说明**

记录 Redis Stream、重试配置、死信 Stream 和验证命令。

- [x] **Step 3: 运行完整验证**

运行目标单元测试、`mvnw.cmd -pl project -am test` 和 `git diff --check`。若环境无法运行 Java 或 Maven，记录具体阻塞信息。

- [ ] **Step 4: 提交变更**

提交信息使用 `feat: add LinkFlow stats retry and dead letter handling`。
