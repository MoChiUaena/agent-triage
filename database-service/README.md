# 数据库连接池样例

独立 Spring Boot 服务，使用 HikariCP 和内存 H2。`GET /api/accounts/1` 实际获取 JDBC 连接并查询示例账户；账户内容仅为固定测试数据。

```powershell
.\mvnw.cmd -f database-service/pom.xml verify
java -jar database-service/target/triage-database-service-0.10.0.jar
```

默认监听 `127.0.0.1:18096`，连接池上限为 2，获取连接超时为 350ms。请求耗时和获取连接耗时来自实际请求，池使用数与等待数每 50ms 从 HikariCP 读取。`/actuator/metrics` 可查看连接池指标；错误事件写入忽略的 `data/database-errors.jsonl`。

`GET /triage/observations?windowMinutes=5&endTime=...` 返回 V2 数据库观测，包含严格时间窗口、请求数、获取连接与查询阶段的错误数、p95，以及窗口内连接使用和等待峰值。错误事件最多 3 条，使用明确的事件代码区分获取连接超时、获取连接失败和 SQL 查询失败。

本机控制请求需要 `X-Triage-Lab: 1`。`POST /lab/reset` 释放持有连接并清空窗口观测；`POST /lab/scenario` 接受以下请求体：

| 请求体 | 实际操作 |
|---|---|
| `{"scenario":"NORMAL"}` | 关闭实验持有的连接，后续请求正常查询 |
| `{"scenario":"DB_POOL_EXHAUSTED"}` | 借出全部池连接，后续请求在获取连接时超时 |
| `{"scenario":"DB_QUERY_LOCK_WAIT"}` | 用事务锁住示例行，后续请求获取连接成功，在 SQL 阶段等待锁后失败 |

故障连接最长持有 10 秒，随后自动释放；也可随时切换 `NORMAL`。SQL 锁等待作为误判检查，不应被诊断为获取连接超时。设置 `--sample.lab-enabled=false` 可关闭全部控制接口，保留业务请求和只读观测接口。

服务标识和数据库标识可通过 `--sample.service-id`、`--sample.database-id` 配置。重新启动后统计从本进程开始，旧日志文件保留，观测不会混入旧进程的错误。
