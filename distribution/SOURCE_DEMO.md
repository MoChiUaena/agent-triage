# 工单源码排查样例

启动演示包后，工单服务在基础端口 +10，分配服务在基础端口 +12。默认分别是 18090、18092。分配服务延迟 700 ms，工单客户端读取超时为 250 ms，因此工单详情请求会实际发生 HTTP 超时。

先访问两个接口，各产生两次请求：

```powershell
1..2 | ForEach-Object { Invoke-RestMethod http://127.0.0.1:18090/api/tickets/summary }
1..2 | ForEach-Object { try { Invoke-RestMethod http://127.0.0.1:18090/api/tickets/T-1 } catch { Write-Host '预期的下游超时：504' } }
```

macOS / Linux：

```bash
curl http://127.0.0.1:18090/api/tickets/summary
curl http://127.0.0.1:18090/api/tickets/summary
curl http://127.0.0.1:18090/api/tickets/T-1
curl http://127.0.0.1:18090/api/tickets/T-1
```

1. 打开 Agent 的“项目源码”页，登记解压目录中的 `projects/ticket-service`，绑定“工单源码样例”。
2. 点击“检查接入”，选最近五分钟，核对服务观测、流量、源码入口和构建摘要。
3. 返回服务排查，选择“工单源码样例”，刷新接口列表。分别选择 `GET /api/tickets/summary` 和 `GET /api/tickets/{id}`，勾选“本次检索源码”。
4. 使用问题“工单请求为什么变慢？”。正常接口没有下游超时；详情接口会显示实际超时、业务调用位置及 `AssignmentGateway.lookup` 对应源码行。
5. 在“代码引用”查看构建源码摘要和静态调用关系，在历史页面按接口筛选和查看统计。整个过程使用固定规则，不需要 API Key。

可再验证版本差异：给 `projects/ticket-service/src/main/java/example/helpdesk/AssignmentGateway.java` 末尾加一行注释，重新索引，然后重新检查接入。已运行的工单 JAR 保持原构建，当前源码摘要不同，页面会提示不一致并停止展开该次调用关系。旧历史保留修改前的结果。恢复文件后重新索引即可再排查。

包内源码和运行 JAR 来自同一构建；源码不会自动上传到模型。模型读取需要另行配置并授权，可能产生费用。本例说明的是本地 HTTP 请求、错误位置和源码核对，不能证明跨服务内部根因。
