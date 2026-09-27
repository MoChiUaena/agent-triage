# API

默认地址为 `http://127.0.0.1:18080`，接口暂未实现身份认证。

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

返回 `202 Accepted`，响应头 `Location` 指向 `/api/runs/{id}`，响应体为提交时的 QUEUED 记录。任务异步执行，随后查询时可能已经完成。

问题须为 1–200 字符且不能全为空白；服务只支持 `order-service`，窗口范围 1–60 分钟，场景为 `NORMAL` 或 `DOWNSTREAM_TIMEOUT`。参数错误返回 400，队列满返回 429。

每次运行以提交时刻为窗口终点，起点为终点减去 windowMinutes。

## 查询与订阅

| 请求 | 结果 |
|---|---|
| `GET /api/demo` | DEMO 模式、合成数据标记、服务、场景、工具列表 |
| `GET /api/runs?limit=20` | 最近执行摘要，limit 为 1–50 |
| `GET /api/runs/{uuid}` | 完整执行记录，包含事件、证据、结论或失败信息 |
| `GET /api/runs/{uuid}/events` | SSE 事件流，支持 `Last-Event-ID` 重放 |

不存在的记录返回 404，非法 UUID 返回 400。错误使用 `application/problem+json`，不回显用户问题或工具原始异常。

SSE `progress` 事件的 `id` 是从 1 开始的本次事件序号。以下省略部分 JSON 字段：

```text
id:3
event:progress
data:{"sequence":3,"type":"TOOL_STARTED","tool":"search_runbooks",...}

event:complete
data:{"id":"...","status":"SUCCEEDED",...}
```

`complete` 携带最终执行记录，发送后关闭连接。失败和证据不足也会发送 complete，具体结果由 status 区分。

已结束的执行仍可订阅。服务端先重放尚未收到的 progress，再发送 complete。`Last-Event-ID` 的有效范围为 0 到已保存的事件数；即使所有 progress 都已接收，重连时仍会返回 complete。

## 工具

所有工具接收 `ToolContext(service, windowMinutes, scenario, endTime)`。服务、场景和时间窗口在提交后保持不变。

| 工具 | 额外输入 | 返回上限 | 证据 ID |
|---|---|---|---|
| `search_runbooks` | query：1–200 字符 | 3 个文档，每篇 ≤ 2400 字符 | `DOC-…#v1` |
| `read_service_metrics` | 无，忽略 query 参数 | 1 条窗口聚合 | `METRICS-ORDER-{scenario}` |
| `query_error_logs` | 无，忽略 query 参数 | 1 条结果，含至多 3 条样例日志 | `LOGS-ORDER-{scenario}` |

证据包含 `id`、`source`、`title`、`summary` 和 `data`，其中 data 带有 synthetic 标记。文档 ID 跨运行保持不变，观测 ID 只在本次执行内解析。工具由执行器调用，没有单独的 HTTP 接口。

## 结果结构

`diagnosis` 包含以下字段：

| 字段 | 内容 |
|---|---|
| `observations` | 观察结果，每项包含 text 和 evidenceIds |
| `possibleCauses` | 可能原因，每项包含 text 和 evidenceIds |
| `nextSteps` | 下一步验证建议 |
| `uncertainty` | 当前证据无法判断的部分 |

失败时 diagnosis 为 null，failure 包含 code 和 message；已采集的证据会保留。

常见失败代码：`TOOL_CALL_LIMIT`、`TOOL_TIMEOUT`、`RUN_TIMEOUT`、`TOOL_ERROR`、`TOOL_OUTPUT_LIMIT`、`RUN_QUEUE_FULL`、`TOOL_CAPACITY`、`SERVER_RESTARTED`。未知执行异常使用 `EXECUTION_ERROR`。

完整响应见[超时场景记录](examples/timeout-run.json)。
