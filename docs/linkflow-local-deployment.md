# LinkFlow Windows 本地部署与验收

本指南已从本地 `main` 的修复提交 `336a4c2` 在干净工作树重新验收。完成后，你可以从浏览器注册、建链、访问短链、查看统计，并用两个账号验证死信权限。先走**聚合模式加本机静态服务发现**，需要拆分服务时看最后的部署变体。业务步骤对应 [项目解析](linkflow-architecture-and-implementation.md)。

## 0. 先认识需要运行的东西

| 组件 | 本机地址或端口 | 你为什么要启动它 |
| --- | --- | --- |
| MySQL 8.0 | `127.0.0.1:3306` | 保存账号、分组、短链和统计。本指南使用独立数据库 `linkflow_lab` |
| Redis | `6379`，需要跨 WSL 转发时用 Windows `16379` | 会话、跳转缓存、锁、去重集合和统计消息 |
| `aggregation` | `127.0.0.1:8003` | 管理、短链和统计后端。访客点击短链时直接访问它 |
| `gateway` | `127.0.0.1:8000` | 管理端 API 的网关入口和登录校验 |
| Vite 前端 | `127.0.0.1:5173` | 你在浏览器操作的后台页面。开发时将 `/api` 转发到网关 |
| Nacos | 默认 `8848` | 采用 Nacos 服务发现时启动。本指南首选路径用本机静态服务发现 |

### 从本地 main 开始

本地修复提交补齐了 `console-vue/index.html`、`HashUtil.java`、`LinkUtil.java`、核心服务启动类、完整 npm 锁文件和构建忽略规则。可以直接在本地 `main` 运行。若要模拟全新检出，可从 `main` 创建独立工作树。本次验收用了没有旧 `target` 和 `node_modules` 的工作树。GitHub 的 `ee688f6` 先于这次本地修复，从 GitHub 新克隆时需确认包含修复提交。

下文命令默认在 Windows PowerShell 中运行。要复现干净工作树验收，执行下面的整段命令。直接运行本地 `main` 时进入项目根目录，并从设置 `$Repo` 开始执行。

```powershell
Set-Location 'D:/tools/CodexArea/Project/shortlink'
$RunRepo = 'D:/tools/CodexArea/Project/LinkFlow-Lab'
git worktree add --detach "$RunRepo" main
Set-Location "$RunRepo"
$Repo = (Get-Location).Path
$Cfg = Join-Path $env:LOCALAPPDATA 'LinkFlow-Lab'
New-Item -ItemType Directory -Path $Cfg -Force | Out-Null
git rev-parse --short HEAD
Get-Item pom.xml, resources/database/link.sql, console-vue/index.html, console-vue/package-lock.json
Get-Item project/src/main/java/com/nageoffer/shortlink/project/toolkit/HashUtil.java
Get-Item project/src/main/java/com/nageoffer/shortlink/project/toolkit/LinkUtil.java
```

**继续条件**：`git rev-parse --short HEAD` 显示 `336a4c2` 或之后的提交，所列文件都存在。`$Repo` 指选中的运行目录。`$Cfg` 是仓库外的本机配置目录，稍后会存 MySQL 连接配置。打开新终端时进入同一个运行目录，恢复 `$Repo` 和 `$Cfg`，工作树只创建一次。

## 1. 准备 JDK、Maven 和 Node.js

1. 安装 **JDK 17**。从 IntelliJ IDEA 的项目 SDK 中选择同一个 JDK 17，也让 PowerShell 中 `java` 和 Maven 使用它。如果 `java -version` 显示其他版本，先检查 `Get-Command java`、`$env:JAVA_HOME` 和环境变量 `Path` 的顺序。 [Microsoft OpenJDK 下载](https://learn.microsoft.com/en-us/java/openjdk/download)
2. 安装 Maven，把 Maven 的 `bin` 加入 `Path`，重新打开 PowerShell。也可以在 IntelliJ IDEA 的 Maven 设置里选择已安装的 Maven 或 IDE 自带的 Maven。 [Maven 安装说明](https://maven.apache.org/install.html)
3. 使用 Windows 的 Node.js 与 npm 安装、运行前端。上次联调使用 Node.js `24.16.0` 和仓库锁定的前端依赖。 [Node.js 下载](https://nodejs.org/en/download)

```powershell
java -version
javac -version
mvn.cmd -v
node --version
npm.cmd --version
```

**继续条件**：`javac` 能执行，且 `mvn.cmd -v` 输出中的 Java version 是 17。用 `npm.cmd` 可避免 Windows PowerShell 拦截 `npm.ps1` 启动脚本。后续步骤使用仓库根 `pom.xml` 定义的模块版本。

## 2. 准备 MySQL 并导入表结构

### 2.1 确认 MySQL 可以连接

安装 MySQL 8.0，配置本机开发账号。安装器设置过的密码，就是下面 `-p` 后要在提示框输入的密码。已有 **MySQL80** 服务时，先查看它的状态。服务名因安装方式而异。

```powershell
Get-Service -Name MySQL80
Test-NetConnection 127.0.0.1 -Port 3306 -InformationLevel Quiet
mysql.exe -h 127.0.0.1 -P 3306 -u root -p --execute "SELECT VERSION();"
```

若服务未运行，在有权限的 PowerShell 中启动实际服务名，例如 `Start-Service MySQL80`。若能连端口但出现 `Access denied`，先订正客户端用户名、密码与本机授权。若系统找不到 `mysql.exe`，将 MySQL 安装目录的 `bin` 加入 `Path`，或使用客户端的完整路径。 [MySQL 8.0 Windows 安装说明](https://dev.mysql.com/doc/refman/8.0/en/windows-installation.html)

### 2.2 建立独立演练库

本指南默认使用 `linkflow_lab`。以下两条命令从仓库根目录运行。各自输入 MySQL 密码。若第一条提示数据库已存在，先确认它是否就是此前初始化的测试库。要另开一轮演练，可更换库名，并同步替换后续 JDBC 配置。 [建表脚本](../resources/database/link.sql)

```powershell
mysql.exe -h 127.0.0.1 -P 3306 -u root -p --execute "CREATE DATABASE linkflow_lab CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
mysql.exe -h 127.0.0.1 -P 3306 -u root -p --default-character-set=utf8mb4 linkflow_lab --execute "source resources/database/link.sql"
mysql.exe -h 127.0.0.1 -P 3306 -u root -p -N --execute "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='linkflow_lab' AND TABLE_TYPE='BASE TABLE';"
```

**继续条件**：当前仓库脚本导入后应有 73 张基础表。它们包括 `t_user_0 … t_user_15`、`t_group_0 … t_group_15`、`t_link_0 … t_link_15` 和 `t_link_goto_0 … t_link_goto_15`。发布版只导入表结构，后面从页面注册测试账号。

### 2.3 生成仓库外的 ShardingSphere 配置

启动类经 ShardingSphere JDBC 驱动读取分片 YAML。为了明确数据库名和凭据，这里复制 [聚合模块配置](../aggregation/src/main/resources/shardingsphere-config-dev.yaml) 到你的本机配置目录，并在启动参数指定绝对路径。YAML 中含数据库凭据和 AES 密钥，保存在仓库外。后续重新启动时沿用同一份配置，重新生成 AES 密钥会影响已有加密字段的读取。

下列脚本为新测试库生成一次配置，其中 `Read-Host -AsSecureString` 让输入密码时不回显。如果使用专门的 MySQL 测试账号，将 `root` 换成该账号，并让它拥有测试库上的业务读写权限。

```powershell
$ShardFile = Join-Path $Cfg 'linkflow-sharding.yaml'
if (!(Test-Path -LiteralPath $ShardFile)) {
    $passwordInput = Read-Host '输入测试库 MySQL 账号密码' -AsSecureString
    $dbPassword = [System.Net.NetworkCredential]::new('', $passwordInput).Password
    $aesKey = [Guid]::NewGuid().ToString('N').Substring(0, 16)
    $template = [IO.File]::ReadAllText(
        (Join-Path $Repo 'aggregation/src/main/resources/shardingsphere-config-dev.yaml')
    )
    $localYaml = $template.Replace('/link?', '/linkflow_lab?').
        Replace('${DB_PASSWORD:}', (ConvertTo-Json -InputObject $dbPassword -Compress)).
        Replace('${DB_AES_KEY:}', (ConvertTo-Json -InputObject $aesKey -Compress)).
        Replace('sql-show: true', 'sql-show: false')
    [IO.File]::WriteAllText($ShardFile, $localYaml)
    Remove-Variable passwordInput, dbPassword, aesKey, template, localYaml
}
$ShardUrl = 'jdbc:shardingsphere:absolutepath:' + $ShardFile.Replace('\', '/')
Test-Path -LiteralPath $ShardFile
```

**继续条件**：最后一行返回 `True`。后续只需要把 `$ShardUrl` 传给聚合服务，终端无需打印 YAML 内容。观察分片 SQL 时可在本机配置中临时开启 `sql-show`，测试结束后关回去。

## 3. 启动 Redis 并确认 Windows 能访问

### 3.1 先测试现有实例

已有 Redis 时先试 Windows 端口：

```powershell
Test-NetConnection 127.0.0.1 -Port 6379 -InformationLevel Quiet
```

使用 WSL Ubuntu 安装 Redis 时，可从 Windows PowerShell 验证 WSL 内部服务：

```powershell
wsl --list --verbose
wsl -d Ubuntu -- redis-cli PING
```

如果还没有 WSL，可先按官方说明执行 `wsl --install -d Ubuntu` 并完成初始化，再按 Redis 的 Windows/WSL 安装文档安装。已有 Ubuntu Redis 服务但未运行时，在 Ubuntu 终端按发行版服务配置执行 `sudo service redis-server start`，再用 `redis-cli PING` 核验。 [安装 WSL](https://learn.microsoft.com/en-us/windows/wsl/install) · [Redis Windows 安装说明](https://redis.io/docs/latest/operate/oss_and_stack/install/install-redis/install-redis-on-windows/)

**分两种情况**：

- Windows 能连 `6379`，使用 `$RedisPort = 6379`，继续 3.3。
- WSL 内返回 `PONG`，Windows 连接 `6379` 却失败，先做 3.2。本机上次联调属于这种情况。

### 3.2 WSL 有 Redis，但 Windows 访问不到时

这个小转发在 Windows 监听 `127.0.0.1:16379`，每个连接通过 WSL 的 `nc` 访问 Ubuntu 内的 `127.0.0.1:6379`。Redis 服务仍维持原来的监听配置。先用 `wsl -d Ubuntu -- which nc` 检查工具，缺少时在 Ubuntu 终端运行 `sudo apt-get install netcat-openbsd`。如果你的发行版名不是 `Ubuntu`，改用 `wsl --list --verbose` 给出的名称。

把下段代码保存为 **配置目录下的** `redis-bridge.mjs`。在专用 PowerShell 窗口用 `node.exe (Join-Path $Cfg 'redis-bridge.mjs')` 启动并保持窗口运行。

```javascript
import net from 'node:net'
import { spawn } from 'node:child_process'

const server = net.createServer((socket) => {
  const redis = spawn('wsl.exe', ['-d', 'Ubuntu', '--', 'nc', '127.0.0.1', '6379'], {
    windowsHide: true,
    stdio: ['pipe', 'pipe', 'inherit']
  })
  socket.pipe(redis.stdin)
  redis.stdout.pipe(socket)
  socket.on('error', () => redis.kill())
  redis.stdin.on('error', () => socket.destroy())
  redis.on('error', () => socket.destroy())
  redis.on('close', () => socket.destroy())
  socket.on('close', () => redis.kill())
})

server.listen(16379, '127.0.0.1', () => console.log('Redis 本机转发已启动'))
```

再开一个窗口验证 `Test-NetConnection 127.0.0.1 -Port 16379 -InformationLevel Quiet` 返回 `True`，后续使用 `$RedisPort = 16379`。

### 3.3 指定同一个测试逻辑库

网关用 Redis 查登录会话，聚合服务用它保存会话、短链缓存和统计。两个服务必须连接同一实例、端口和逻辑库。下例用空闲的 15 号库，示例以 WSL Redis 校验该库是否已有数据。

```powershell
$RedisPort = 6379   # 使用上一节转发时改为 16379
$RedisDb = 15
wsl -d Ubuntu -- redis-cli -n 15 DBSIZE
```

若测试库不是为你准备的空库，换一个空闲的逻辑库，并同步替换后续所有对应的 `15`。对于已有数据的 Redis，保留其原键。

**Redis 有密码**时，在两个后端的启动配置中设置同一个 `REDIS_PASSWORD`，并传入下面第 4 节中的端口和逻辑库参数。

**Redis 无密码**时，这个版本的 Redisson 可能对默认空密码配置发出 `AUTH`。本地联调使用下面的 Redisson 文件指定地址与逻辑库。配置单位是聚合服务，网关仍用自己的 `spring.data.redis.*` 连接参数。

```powershell
$RedissonFile = Join-Path $Cfg 'linkflow-redisson.yaml'
@"
singleServerConfig:
  address: "redis://127.0.0.1:$RedisPort"
  database: $RedisDb
  connectionMinimumIdleSize: 2
  connectionPoolSize: 8
"@ | Set-Content -LiteralPath $RedissonFile -Encoding UTF8
$RedissonUri = ([Uri]$RedissonFile).AbsoluteUri
```

## 4. 按顺序启动两个 Java 服务

以下参数对应主路径 **聚合模式、无 Nacos、无密码 Redis**。配置留在进程参数里，同一套仓库可继续用于其他环境。若 Redis 有密码，删去聚合参数里 `--spring.redis.redisson.file=...` 那一项，让两个后端用各自环境中的 `REDIS_PASSWORD`。

在保存了 `$Cfg`、`$ShardUrl`、`$RedisPort`、`$RedisDb` 和 `$RedissonUri` 的 PowerShell 中生成参数。

```powershell
$AggregateArgs = @(
    '--server.address=127.0.0.1'
    '--server.port=8003'
    "--spring.datasource.url=$ShardUrl"
    "--spring.data.redis.port=$RedisPort"
    "--spring.data.redis.database=$RedisDb"
    "--spring.redis.redisson.file=$RedissonUri"
    '--spring.cloud.nacos.discovery.enabled=false'
    '--spring.cloud.nacos.discovery.register-enabled=false'
    '--short-link.demo-mode.enable=false'
    '--short-link.domain.default=127.0.0.1:8003'
)

$GatewayArgs = @(
    '--server.address=127.0.0.1'
    '--server.port=8000'
    '--spring.profiles.active=aggregation'
    "--spring.data.redis.port=$RedisPort"
    "--spring.data.redis.database=$RedisDb"
    '--spring.cloud.nacos.discovery.enabled=false'
    '--spring.cloud.nacos.discovery.register-enabled=false'
    '--spring.cloud.discovery.client.simple.instances.short-link-aggregation[0].uri=http://127.0.0.1:8003'
)

($AggregateArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
($GatewayArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
```

静态实例配置告诉网关，路由中的 `lb://short-link-aggregation` 指向本机 `8003`。原有管理路径和 Token 校验继续生效。 [Spring Cloud 服务发现说明](https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/common-abstractions.html)

### 4.1 IntelliJ IDEA 启动方式

1. 在 IDEA 打开根 `pom.xml`，按 Maven 项目导入。等待模块和依赖加载完成。在 **Project Structure → Project SDK** 选择 JDK 17。
2. 打开 [AggregationServiceApplication](../aggregation/src/main/java/com/nageoffer/shortlink/aggregation/AggregationServiceApplication.java)，通过主方法旁的运行入口创建 Application 配置。进入 **Run → Edit Configurations**，确认模块为 `aggregation`，JRE 为 JDK 17，将上面的 `$AggregateArgs` 输出完整粘贴到 **Program arguments**。
3. 聚合服务的 **Environment variables** 可以设置 `LINKFLOW_STATS_DEAD_LETTER_OPERATORS=lf_e2e_ops`。这一步只指定用户名，账号稍后注册。多个用户名用英文逗号分隔。需要地区查询时配置你自己的 `AMAP_KEY`。临时凭据通过本机运行配置注入。
4. 先运行聚合服务。日志出现 `Started AggregationServiceApplication` 后，从 Windows 测试 `curl.exe --noproxy "*" -i http://127.0.0.1:8003/actuator/health`。应为 HTTP 200，正常的健康响应包含 `"status":"UP"`。
5. 打开 [GatewayServiceApplication](../gateway/src/main/java/com/nageoffer/shortlink/gateway/GatewayServiceApplication.java)，类似地创建 `gateway` 模块的 Application 配置，把 `$GatewayArgs` 输出粘贴到 **Program arguments**。运行后应看到网关监听 `8000`。
6. 测试 `curl.exe --noproxy "*" -i http://127.0.0.1:8000/api/short-link/admin/v1/stats/dead-letters`。这条请求没有会话，返回 HTTP 401 说明网关已接到请求并执行登录校验。它不能独立证明网关已成功转发到业务进程，后面的登录和建链负责验证转发。

[IDEA 程序参数与环境变量](https://www.jetbrains.com/help/idea/program-arguments-and-environment-variables.html) · [高德 Web 服务 IP 定位](https://lbs.amap.com/api/webservice/guide/api/ipconfig)

### 4.2 不用 IDE 时先打包再启动

任选本节和 4.1 的一种方式。在 Windows 命令行上，`spring-boot:run` 会为聚合模块展开依赖类路径，本次干净工作树触发了 `CreateProcess error=206`。以下路线先将依赖打入可执行 JAR，再用 `java -jar` 启动，已用 JDK 17 验收。

在项目根目录执行。第一条将聚合模块的兄弟模块安装到本机 Maven 仓库，同时运行统计重试测试。后两条依次编译、生成普通 JAR、重打包。三条命令都应显示 `BUILD SUCCESS`。

```powershell
mvn.cmd -pl admin,project -am install "-Dmaven.compiler.encoding=UTF-8" "-Dproject.build.sourceEncoding=UTF-8"
mvn.cmd -f aggregation/pom.xml compile org.apache.maven.plugins:maven-jar-plugin:3.4.1:jar org.springframework.boot:spring-boot-maven-plugin:3.0.7:repackage "-Dmaven.compiler.encoding=UTF-8" "-Dproject.build.sourceEncoding=UTF-8"
mvn.cmd -f gateway/pom.xml compile org.apache.maven.plugins:maven-jar-plugin:3.4.1:jar org.springframework.boot:spring-boot-maven-plugin:3.0.7:repackage "-Dmaven.compiler.encoding=UTF-8" "-Dproject.build.sourceEncoding=UTF-8"
Get-ChildItem aggregation/target/shortlink-aggregation.jar, gateway/target/shortlink-gateway.jar
```

在两个终端分别启动。进入项目根目录并重新定义第 4 节对应的参数数组。聚合服务健康检查返回 200 后再开网关。

```powershell

# 窗口 A
$env:LINKFLOW_STATS_DEAD_LETTER_OPERATORS = 'lf_e2e_ops'
java.exe '-Dfile.encoding=UTF-8' '-Duser.timezone=Asia/Shanghai' -jar 'aggregation/target/shortlink-aggregation.jar' @AggregateArgs

# 窗口 B，在聚合服务就绪之后运行
java.exe '-Dfile.encoding=UTF-8' '-Duser.timezone=Asia/Shanghai' -jar 'gateway/target/shortlink-gateway.jar' @GatewayArgs
```

两个 Java 进程会让各自的终端持续运行。需要密码或高德 Key 时，在对应服务终端的环境变量里注入。健康检查和网关 401 检查命令沿用第 4.1 节。 [Spring Boot Maven 打包插件](https://docs.spring.io/spring-boot/docs/3.0.7/maven-plugin/reference/htmlsingle/)

## 5. 启动管理端前端

在 PowerShell 的第三个窗口进入 `console-vue`。

```powershell
Set-Location "$Repo/console-vue"
npm.cmd ci --ignore-scripts --no-audit --no-fund
npm.cmd run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

第二条命令按现有 `package-lock.json` 安装依赖，这里沿用本机验证过的 `--ignore-scripts` 路径。仓库同时有 `sass` 与旧的 `node-sass` 依赖，这条路径可减少旧依赖安装脚本在 Windows 上产生的阻碍。打开 `http://127.0.0.1:5173/`。Vite 把 `/api` 转到 `8000`。如果指定的端口已被占用，先检查是哪一个本机进程，再决定复用还是按需更换配置。 [npm ci 命令](https://docs.npmjs.com/cli/commands/npm-ci/) · [项目 Vite 代理](../console-vue/vite.config.js)

## 6. 逐步验收正常业务

### 6.1 注册账号、登录和查看默认分组

1. 在页面点击「去注册」，新建一个测试账号，例如 `lf_member`。页面限制用户名最多 11 个字符，密码长度 8 到 15 位。使用专用测试密码和资料。
2. 注册成功后进入「我的空间」。预期能看到「默认分组」。如果页面没有进入，打开 F12 → **Network**，分别看注册、登录和分组接口的响应。
3. 查看 `POST /api/short-link/admin/v1/user/login`。成功响应的业务 `code` 是 `"0"`，数据中有 Token。随后 `GET /api/short-link/admin/v1/group` 应返回当前用户的分组。退出后使用旧会话请求管理接口会返回 HTTP 401。
4. 新建一个测试分组并改名。确认列表显示变化，后面可用它验收移动分组。

### 6.2 创建短链并看见 HTTP 302

1. 在「我的空间」选择测试分组，创建单条短链。目标地址可以填写一个后端可访问的有域名网页。仅用本机前端验收时，可用本次通过表单实测的 `http://127.00.00.01:5173/`，它仍指向本机的 `127.0.0.1:5173`，同时满足现有表单对各地址段长度的校验。描述填写「本机学习测试」，有效期选择永久有效。
2. 预期返回 `http://127.0.0.1:8003/<短码>`。在 Network 中可查看 `POST /api/short-link/admin/v1/create` 的请求和响应。保存完整短链供下一步使用。
3. 用另一个 PowerShell 发一次实际访问。示例中的短链替换为管理端返回的值。

```powershell
$ShortUrl = 'http://127.0.0.1:8003/<替换为你的短码>'
curl.exe --noproxy '*' -i "$ShortUrl"
```

**预期**：HTTP 302，`Location` 指向 `http://127.0.0.1:5173/`。地址要和创建时配置的域名、端口一致。把 `127.0.0.1` 改成 `localhost` 会改变跳转服务用于查询的完整短链标识。

### 6.3 检查访问统计

等待统计消费者落库后，在短链统计面板选今天的日期区间并刷新。新短链接只有上述一次访问时，PV 应为 1，明细应有一条。第一次访客和第一次 IP 也会进入对应去重统计。屏幕上的时间以本机当前时区和日期筛选范围为准。

如果要直接对照 MySQL，进入 `linkflow_lab` 后用下面 SQL。完整短链标识按实际地址填写，去掉 `http://`。

```sql
SET @test_url = '127.0.0.1:8003/<替换为你的短码>';
SELECT full_short_url, SUM(pv) AS pv
FROM t_link_access_stats
WHERE full_short_url = @test_url
GROUP BY full_short_url;

SELECT full_short_url, create_time, update_time
FROM t_link_access_logs
WHERE full_short_url = @test_url
ORDER BY id DESC;
```

想核对短链总量时，通过建链的实际 SQL 找到 `t_link_*` 的对应分片，用同一个 `@test_url` 查询 `total_pv`、`total_uv` 和 `total_uip`。 [分片与统计读写路径](linkflow-architecture-and-implementation.md#先记住这几张表)

### 6.4 检查回收站和恢复

把测试短链放入回收站，确认普通列表移除该行，再访问一次短链，应看到跳到 `/page/notfound`。在回收站恢复，确认原地址仍可跳转。最终移除适合另建一条专用测试短链验证，以便留一条正常链接练习统计和编辑。

## 7. 验收死信查询、重放和权限

另建一条全新的测试短链，基线计数保持为 0。保持第一条短链的数据，避免把它的访问混入死信验证。注册用户名为 `lf_e2e_ops` 的测试账号。若第 4 节启动时未配置名单，在聚合进程设置 `LINKFLOW_STATS_DEAD_LETTER_OPERATORS=lf_e2e_ops` 后重启。普通测试账号保持在名单之外。

1. **制造死信**。让测试短链缓存保留目标地址，在隔离的测试库里临时移走该短链的跳转映射，再真实访问一次。跳转仍能返回 302，消费者找不到映射，默认重试 3 次后进入死信。记下死信 ID 和带毫秒的 `currentDate`，然后立即恢复测试映射。
2. **验证普通账号**。普通账号登录后打开右上角「统计死信」，应看到无权限提示。使用这个账号的有效 Token 直接请求重放，也应返回 403。检查死信仍在。
3. **验证运维账号**。用独立浏览器会话登录运维账号，进入同一页面并重放。响应返回新消息 ID 时，等待消费者成功落库再核对统计。
4. **验证只计数一次**。检查短链总 PV、访问聚合 PV、当日 PV、访问明细数均由 0 变 1。再对原死信 ID 发一次重放请求，应返回 404，四个数继续为 1。
5. **验证时间**。把消息里的原访问时间换算成 MySQL 本地时区，在 SQL 中转为 `DATETIME(0)`。访问明细的 `create_time` 应等于这个结果。实测原值 `19:39:43.958` 保存为 `19:39:44`，写入时间是 `19:39:49`。

具体到每条 Redis 命令、测试映射改动及恢复 SQL、普通账号 POST 命令和四项计数 SQL，请逐条按 [统计死信端到端复现](linkflow-stats-retry.md#端到端复现) 操作。保持这次实验中的数据库、Redis 逻辑库和短链一致。

## 8. 常见问题按这个顺序查

| 现象 | 先查什么 |
| --- | --- |
| 网页能开，登录请求失败或显示网关 503 | 先看 `8003/actuator/health`，再核对网关路由为 `aggregation`，静态实例名与端口正确，Vite 代理指向 `8000` |
| 后端启动提示数据源连接失败 | 用 `mysql.exe -p` 核对本机账号，检查绝对路径 YAML 的数据库名、登录账号和 AES 配置，确认初始化了对应的库 |
| 提示 MySQL 找不到 `t_user_*` 等表 | 查看服务实际连接的 schema。确认 `resources/database/link.sql` 导入到该库，而不是别的实例 |
| Redis refused 或超时 | 先看 WSL 内 `PING`，再在 Windows 测试实际端口。需要时确认转发窗口仍运行 |
| Redis 提示 `AUTH called without any password` | 无密码实例使用第 3 节的 Redisson 配置，确认程序参数的文件路径和 Redis 端口 |
| 普通请求登录后变为 401 | 在浏览器查看请求是否带 `Username`、`Token`，核对网关与聚合服务使用同一个 Redis 逻辑库并重新登录 |
| 运维页面返回 403 | 检查启动聚合服务的进程是否拿到了运维名单，确认用户名拼写和当前登录账号一致。修改后重新启动服务 |
| 短链指向外部默认域名，或本机短链跳不到原地址 | 核对 `short-link.domain.default`，用实际配置的主机名和端口重新创建并访问短链 |
| 访问是 302，但过一会儿统计仍为 0 | 看聚合服务中的统计消费者日志，再查看统计死信。确认分组映射存在、高德调用能返回可解析的响应，以及面板日期范围正确 |
| `npm ci` 或 Vite 启动失败 | 从 `console-vue` 运行 `npm.cmd`，保留仓库锁文件，用第 5 节验证过的安装选项。核对 Node.js 版本和端口 |
| Maven 在 Lombok、编译或中文编码处报错 | 先用 `mvn.cmd -v` 核对实际 JDK 17，再核对 UTF-8 参数与依赖下载结果 |

## 9. 带 Nacos 运行、拆分和打包

### 9.1 已有 Nacos 时走注册发现

启动现有 Nacos，确认配置中的服务地址，一般是 `127.0.0.1:8848`。从两个后端去掉禁用 Nacos 的参数，也去掉网关的静态实例参数。聚合服务启动后应注册为 `short-link-aggregation`，网关保留 `aggregation` 配置。先启动业务服务并确认注册，再启动网关。

要学习拆分方式，启动 `ShortLinkAdminApplication`（`8002`）和核心模块的 [ShortLinkApplication](../project/src/main/java/com/nageoffer/shortlink/project/ShortLinkApplication.java)（`8001`），网关使用 `--spring.profiles.active=dev`。为各业务模块分别准备自己的 ShardingSphere 配置。两个模块的逻辑表规则有差异，应分别从各自资源目录下的开发配置副本调整连接参数。统计死信账号名单在 `admin` 配置，短链域名在 `project` 设置为 `127.0.0.1:8001`。管理端和消费者仍共用 Redis 实例及逻辑库。 [拆分路由](../gateway/src/main/resources/application-dev.yaml)

### 9.2 发布前端静态文件

后端按第 4.2 节打包并启动。前端在 `console-vue` 运行 `npm.cmd run build` 生成 `dist`。通过静态服务器发布时，另外配置 `/api` 到网关的代理和 Vue 路由回退。

## 10. 停止本机实验并记录验证环境

在各自终端按 Ctrl+C 或用 IDEA 的停止按钮关闭前端、网关、聚合服务和可选 Redis 转发。MySQL 或 Redis 如果原本就在运行，可保留服务。独立测试库和测试 Redis 键按需保留供下次练习，记录库名、短链 ID、死信 ID 和测得的统计用于回看。

2026-11-25 从修复提交 `336a4c2` 的干净工作树完成验收。环境为 JDK 17.0.21.1、MySQL 8.0（隔离实例 `13306`）、WSL Redis 8 和 Node.js 24.16.0。后端 4 个统计重试测试通过，聚合和网关的可执行 JAR 均成功启动，前端从锁文件安装依赖并完成生产构建。

在真实浏览器中验证了注册登录、建链、跳转、统计和回收站。普通账号查询与重放返回 403。运维重放后四项统计从 0 到 1，重复重放返回 404。原访问时间 `20:59:59.200`，MySQL 明细为 `20:59:59`，重放写入时间为 `21:00:03`。本次完整验收对应聚合模式和本机静态服务发现。此前的测试步骤与 SQL 可对照 [统计死信运行文档](linkflow-stats-retry.md#本次联调记录)。
