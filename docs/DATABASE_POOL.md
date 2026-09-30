# 数据库连接池排查

`account-service` 使用 HikariCP 和内存 H2 实际处理查询。连接池耗尽时，请求在获取连接阶段等待并返回 503；SQL 行锁等待发生在连接获取之后，单独记录查询失败。

## 启动

```powershell
.\mvnw.cmd -f database-service/pom.xml verify
java -jar database-service/target/triage-database-service-0.5.0.jar
```

带服务列表启动 Agent，其他样例服务启动方式不变：

```powershell
java -jar target/agent-triage-0.5.0.jar --spring.config.additional-location=file:./examples/services.yml
```

页面选择“数据库账户样例”。可以生成正常请求、触发连接池耗尽，或释放连接并验证恢复。恢复操作先确认一次真实获取连接超时，再释放连接、清空样例窗口并执行新的正常请求；Agent 中已有的排查记录保留。

Agent 的三个工具仍只读。实验控制需要显式的 `lab-enabled: true` 与本机同源请求头；关闭该配置后，观测和排查仍可使用，页面隐藏实验按钮。样例持有的故障连接最长 10 秒，超时会自动释放。

## 观测契约

登记服务时使用 `protocol: DATABASE_V2`，`downstream-id` 填数据库标识。地址、时间窗口与白名单限制沿用[服务接入说明](SERVICE_INTEGRATION.md)。接口为 `GET /triage/observations?windowMinutes=5&endTime=...`：

```json
{
  "schemaVersion": 2,
  "kind": "DATABASE_POOL",
  "service": "account-service",
  "database": "accounts-db",
  "windowStart": "2026-09-28T13:35:00Z",
  "windowEnd": "2026-09-28T13:40:00Z",
  "requestCount": 3,
  "recordedRequestCount": 3,
  "requestP95Ms": 354.1,
  "baselineRequestP95Ms": null,
  "databasePool": {
    "maximumConnections": 2,
    "peakActiveConnections": 2,
    "peakPendingThreads": 1,
    "poolSamples": 30,
    "exhaustedSamples": 20,
    "acquisitionTimeoutCount": 3,
    "acquisitionErrorCount": 0,
    "queryCount": 0,
    "queryErrorCount": 0,
    "acquisitionP95Ms": 354.1,
    "queryP95Ms": 0
  },
  "errors": [{
    "timestamp": "2026-09-28T13:39:59Z",
    "traceId": "example-db-trace",
    "level": "ERROR",
    "message": "HikariCP connection acquisition timed out",
    "code": "DB_CONNECTION_ACQUIRE_TIMEOUT"
  }],
  "synthetic": false
}
```

以上数值是格式示例。窗口须与请求完全一致，计数非负，耗时为有限非负数。池上限为 1–64，使用峰值不能超过上限；`exhaustedSamples` 统计同一次采样中“池满且有等待”的次数，不能用两个独立峰值替代。

`queryCount` 等于窗口请求数减去获取连接超时和获取连接失败数。SQL p95 只统计真正执行过的查询；没有查询时为 0，页面显示“未执行”，不能当成正常基线。获取连接与 SQL 错误互不混算，错误代码分别为 `DB_CONNECTION_ACQUIRE_TIMEOUT`、`DB_CONNECTION_ACQUIRE_FAILED`、`SQL_QUERY_FAILED`。错误最多 3 条，时间须在窗口内；消息和总响应大小限制与 V1 相同。

## 判断边界与验证

成功的耗尽判断同时要求：窗口有请求、获取连接超时、池满与等待重叠的采样、对应阶段的错误事件及排障规则。证据不满足时返回证据不足。满载峰值、SQL 慢或 SQL 报错不能单独证明池耗尽，也不能据此确认连接泄漏。

SQL 执行失败使用另一条规则：窗口内真正执行了查询、查询失败次数大于零、获取连接超时与失败均为零，指标和日志计数一致，且错误事件包含 `SQL_QUERY_FAILED`。结果只确认失败发生在获取连接之后；它不证明锁等待、语法错误、索引问题或数据库内部原因。若两种阶段的错误混在同一窗口，不能强行归入单一 SQL 判断。

窗口峰值不代表当前仍处于故障。没有超时只能说明本次采集未发现这类证据，不代表整个数据库健康。样例保留最多 10000 条请求和 72000 次池采样，当前接口面向本地接入实验，尚未实现生产数据库观测适配。

`scripts/db_pool_smoke.py` 检查空窗口、正常、耗尽、恢复、SQL 锁等待，以及引用和历史身份。默认要求 DEMO 模式；`--mock-provider` 仅供已经配置本地协议模拟器的 MODEL 测试。原始运行记录写入忽略的 `target/db-pool-smoke/`，不提交到仓库。
