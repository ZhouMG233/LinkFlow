# LinkFlow 数据库脚本

`resources/database` 保存 LinkFlow 的数据库初始化脚本和公开示例数据说明。

## 文件说明

- `link.sql`：创建用户、分组、短链接、访问统计和访问日志等表结构
- `link-data.example.sql`：公开示例数据格式，不包含真实用户信息或敏感凭据

本地启动前请先创建 `link` 数据库并执行表结构脚本，再通过环境变量配置数据库连接信息。

