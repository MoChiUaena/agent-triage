# HTTP 与工具契约

默认基地址 `http://127.0.0.1:18080`，只用于本地合成演示。当前没有身份认证。

## 提交执行

`POST /api/runs`，`Content-Type: application/json`：

```json
{
  "question": "订单查询接口为什么变慢了？",
  "service": "order-service",
  "windowMinutes": 15,
  "scenario": "DOWNSTREAM_TIMEOUT"
}
```

返回 `202 Accepted`、`Location: /api/runs/{id}` 与提交时的 QUEUED 快照。执行异步进行，立即 GET 可能已经完成。参数：问题 1–200 字符（非纯空白）、唯一服务 `order-service`、1–60 分钟、场景 `NORMAL` / `DOWNSTREAM_TIMEOUT`。错误参数 400，队列满 429。

每次运行以提交时刻为窗口终点，工具查询起点为终点减去 windowMinutes。切换 UI 场景只影响新执行。

## 查询与订阅

| 请求 | 结果 |
|---|---|
| `GET /api/demo` | DEMO 模式、合成标记、服务、场景、工具列表 |
| `GET /api/runs?limit=20` | 最近执行摘要，limit 为 1–50 |
| `GET /api/runs/{uuid}` | 完整执行、事件、证据、结论或失败 |
| `GET /api/runs/{uuid}/events` | SSE；支持 `Last-Event-ID` 重放 |

不存在的记录返回 404，非法 UUID 返回 400。错误使用 `application/problem+json`，不回显用户问题或工具原始异常内容。

SSE `progress` 的 `id` 是从 1 开始的本次事件序号：

```text
id:3
event:progress
data:{"sequence":3,"type":"TOOL_STARTED","tool":"search_runbooks",...}

event:complete
data:{"id":"...","status":"SUCCEEDED",...}
```

`complete` 带整个最终快照，发送后关闭连接。失败和证据不足同样发送 complete，但 status 不同。已结束执行也可订阅；重放尚未收到的 progress 后发送 complete。`Last-Event-ID` 必须在 0 到已保存事件数之间；即使全部 progress 已收到，重连仍返回最终 complete。

## 三个工具

所有工具接收受约束的 `ToolContext(service, windowMinutes, scenario, endTime)`。服务、场景、窗口由服务端冻结，未来不应让模型任意覆盖。

| 工具 | 额外输入 | 返回上限 | 证据 ID |
|---|---|---|---|
| `search_runbooks` | query：1–200 字符 | 3 个文档，每篇 ≤ 2400 字符 | `DOC-…#v1` |
| `read_service_metrics` | 无（共享签名中的 query 忽略） | 1 条窗口聚合 | `METRICS-ORDER-{scenario}` |
| `query_error_logs` | 无（共享签名中的 query 忽略） | 1 条结果，含至多 3 条样例日志 | `LOGS-ORDER-{scenario}` |

证据结构是 `id / source / title / summary / data`；data 内含 synthetic 标记。文档 ID 跨运行稳定，观测 ID 只在本次执行上下文中解析，不是跨运行唯一键。工具没有 HTTP 直通入口，用户不能传入文件路径、SQL、Shell 命令或外部服务地址。

## 结果结构

`diagnosis` 包含 `observations`、`possibleCauses`、`nextSteps` 和 `uncertainty`；观察 / 原因包含 `text` 与 `evidenceIds`。失败时 diagnosis 为 null，failure 包含稳定的 code 和可展示的 message。

常见失败代码：`TOOL_CALL_LIMIT`、`TOOL_TIMEOUT`、`RUN_TIMEOUT`、`TOOL_ERROR`、`TOOL_OUTPUT_LIMIT`、`RUN_QUEUE_FULL`、`TOOL_CAPACITY`、`SERVER_RESTARTED`。未知执行异常使用 `EXECUTION_ERROR`。执行失败保留之前成功采集的证据，但不把未完成推断显示为结论。

完整实际样例见 [超时场景记录](examples/timeout-run.json)。
