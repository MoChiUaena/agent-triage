# 订单服务样例

这是独立运行的订单服务，默认只监听 `127.0.0.1:18082`。`GET /api/orders/{id}` 通过 HTTP 调用另一个 JVM 中的 [库存服务](../inventory-service/README.md)。库存服务正常等待约 15ms；故障场景等待 600ms，超过订单服务 300ms 的请求总时限。请先启动库存服务。

订单服务在有界内存中保留窗口请求耗时，使用 Micrometer 记录请求计数和耗时，并将实际请求错误写入 `data/sample-errors.jsonl`。`GET /lab/observations` 从这些记录汇总窗口指标和最多三条结构化错误日志。

```powershell
.\mvnw.cmd -f sample-service/pom.xml verify
java -jar sample-service/target/triage-sample-service-0.2.0.jar
```

在另一个终端产生流量：

```powershell
Invoke-RestMethod http://127.0.0.1:18082/api/orders/101
Invoke-RestMethod http://127.0.0.1:18082/lab/observations

Invoke-RestMethod http://127.0.0.1:18082/lab/scenario -Method Post -ContentType application/json -Body '{"scenario":"DOWNSTREAM_TIMEOUT"}'
Invoke-WebRequest http://127.0.0.1:18082/api/orders/102 -SkipHttpErrorCheck
Invoke-RestMethod http://127.0.0.1:18082/lab/observations
```

`POST /lab/reset` 清空窗口观测和错误日志，并通过 HTTP 将库存服务切回正常模式。`GET /lab/observations?windowMinutes=15` 返回当前窗口内的请求数、p95、超时率和最多三条错误事件；`GET /lab/errors` 直接读取结构化日志。`GET /actuator/metrics/sample.order.requests` 可核对 Micrometer 的累计请求数。窗口数值来自实际请求，不会预先填充；Micrometer 累计值在进程重启前不会因实验重置而清零。
