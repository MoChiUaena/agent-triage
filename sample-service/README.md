# 订单服务样例

这是一个单独运行的 Spring Boot 服务，默认只监听 `127.0.0.1:18082`。`GET /api/orders/{id}` 会通过 HTTP 调用同一服务中的库存接口。正常模式下库存接口短暂等待；超时模式下等待 600ms，超过订单服务 300ms 的请求总时限。订单服务把实际请求耗时和超时事件保存在有界内存中，并输出错误日志。

```powershell
.\mvnw.cmd -f sample-service/pom.xml verify
java -jar sample-service/target/triage-sample-service-0.1.0-SNAPSHOT.jar
```

在另一个终端产生流量：

```powershell
Invoke-RestMethod http://127.0.0.1:18082/api/orders/101
Invoke-RestMethod http://127.0.0.1:18082/lab/observations

Invoke-RestMethod http://127.0.0.1:18082/lab/scenario -Method Post -ContentType application/json -Body '{"scenario":"DOWNSTREAM_TIMEOUT"}'
Invoke-WebRequest http://127.0.0.1:18082/api/orders/102 -SkipHttpErrorCheck
Invoke-RestMethod http://127.0.0.1:18082/lab/observations
```

`POST /lab/reset` 清空观测并切回正常模式。`GET /lab/observations?windowMinutes=15` 返回当前窗口内的请求数、p95、超时率和最多三条错误事件。数值来自刚才实际处理的请求，不会预先填充。服务重启后内存记录清空；它是本地排障样例，不是生产监控系统。
