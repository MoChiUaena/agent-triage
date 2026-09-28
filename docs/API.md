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

问题须为 1–200 字符且不能全为空白；服务必须已在启动配置中登记，窗口范围为 1–60 分钟且不能超过该服务的限制。默认合成演示的场景为 `NORMAL` 或 `DOWNSTREAM_TIMEOUT`；LIVE 接入时可省略场景。参数错误返回 400，队列满返回 429。

每次运行以提交时刻为窗口终点，起点为终点减去 windowMinutes。

## 查询与订阅

| 请求 | 结果 |
|---|---|
| `GET /api/config` | 当前模式、模型名称（MODEL 模式）、观测来源与可用状态、服务、场景和工具列表 |
| `GET /api/config?service=checkout-service` | 所选服务的配置与接口可用状态；`services` 提供全部可选服务，`labEnabled` 表示是否允许演示控制 |
| `GET /api/demo` | `/api/config` 的兼容入口 |
| `GET /api/runs?limit=20` | 最近执行摘要，limit 为 1–50 |
| `GET /api/runs/{uuid}` | 完整执行记录，包含事件、证据、结论或失败信息 |
| `GET /api/runs/{uuid}/events` | SSE 事件流，支持 `Last-Event-ID` 重放 |

运行模式由服务端配置，提交请求不能切换模式。执行记录和列表摘要的 `mode` 为 `DEMO` 或 `MODEL`。默认合成数据源下 `synthetic` 为 true；启用 LIVE 数据源后为 false。旧 `LAB` 协议读取订单样例当前场景；`OBSERVATIONS_V1` 和 `DATABASE_V2` 只调用观测接口，场景保存为 `OBSERVED`。LIVE 提交体中的 `scenario` 不决定观测值。

新记录和摘要包含 `serviceInfo`，保留执行时的服务与下游名称。旧历史中的该字段可为空，仍能读取。观测地址不会出现在公共配置和执行记录中。[接口契约与接入配置](SERVICE_INTEGRATION.md)另见说明。

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

MODEL 模式还包含 `MODEL_STARTED`、`MODEL_COMPLETED` 和 `MODEL_FAILED` 事件，表示请求边界，不包含模型原始回复或内部思考内容。

`TOOL_ARGUMENTS_REJECTED` 表示整批工具参数未通过校验，实际工具尚未执行。消息只包含应用生成的分类与说明；首次拒绝允许一次模型更正，仍计入原有模型轮次与总时限。再次无效以 `INVALID_TOOL_ARGUMENTS` 失败；服务或窗口不会自动替换。

新成功记录的 `modelExecution.nextChecks` 保存已校验的检查项代码；旧记录可能为 null。重复要求已完成的观测/规则查询，或关联不存在的日志 traceId，会以 `MODEL_CHECKS_MISMATCH` 失败。

`MODEL_MISSING_EVIDENCE` 表示成功判断缺少必需观测或引用。预算允许时，应用最多发送一次 `EVIDENCE_FEEDBACK`；已收集的工具不会重复调用，仍不完整或预算不足则失败。格式错误、未知 ID 和观测矛盾不参与该反馈。

`modelExecution.requestedNextChecks` 保留模型原始有效选项，`nextChecks` 为应用按观测排序后的最多两项。`CHECKS_PRIORITIZED` 标明应用排序；旧记录不会重新排序。LIVE 规则证据的 `queryMatched` 与 `serviceReference` 分别标明关键词命中与本服务基础参考。

新版模型最终只选择判断类型、证据 ID 和检查项，成功解析后记录 `CONCLUSION_RENDERED`；窗口事实及关键诊断措辞由应用生成。对外 diagnosis 结构不变，内部选择格式见[模型判断契约](MODEL_OUTPUT.md)。旧历史不会重写。

若模型调用工具后得到的窗口请求数为 0，应用记录 `EVIDENCE_GATE` 事件并直接返回 `INSUFFICIENT_EVIDENCE`，不再发起最终一轮模型请求。结果中的不确定性会说明结论由应用证据门槛生成。

若已有请求指标且检索到了文档，但没有与当前超时/正常观测相符的规则，应用同样记录 `EVIDENCE_GATE`，返回证据不足；模型不能用错误场景的文档支持成功结论。

明显超出订单与库存排障范围的问题由应用记录 `SCOPE_GATE` 并返回 `INSUFFICIENT_EVIDENCE`，不发起模型请求；该门槛只筛掉明确无关的问题，不能代替模型的语义判断。

## 工具

所有工具接收 `ToolContext(service, windowMinutes, scenario, endTime)`。服务、场景和时间窗口在提交后保持不变。LIVE 数据源通过 HTTP 查询本机订单样例服务的窗口观测；工具本身不切换场景，也不生成请求。

模型请求工具时必须传入 `service` 和 `windowMinutes`，检索工具还需要 `query`。服务和窗口必须与本次请求一致，多余字段和重复参数都会被拒绝。

| 工具 | 额外输入 | 返回上限 | 证据 ID |
|---|---|---|---|
| `search_runbooks` | query：1–200 字符 | 3 个文档，每篇 ≤ 2400 字符 | `DOC-…#v1` |
| `read_service_metrics` | 无，忽略 query 参数 | 1 条窗口聚合 | 合成：`METRICS-ORDER-{scenario}`；LIVE：`METRICS-LIVE-{timestamp}` |
| `query_error_logs` | 无，忽略 query 参数 | 1 条结果，含至多 3 条错误事件 | 合成：`LOGS-ORDER-{scenario}`；LIVE：`LOGS-LIVE-{timestamp}` |

证据包含 `id`、`source`、`title`、`summary` 和 `data`，其中 data 带有 synthetic 标记。LIVE 数据源使用 v2 排障文档；合成模式保留 v1。观测 ID 只在本次执行内解析。工具由执行器调用，没有单独的 HTTP 接口。

## 本地样例控制

数据库样例使用 `service: account-service`，场景为 `NORMAL`、`DB_POOL_EXHAUSTED`、`DB_POOL_RECOVERY`；`DB_QUERY_LOCK_WAIT` 用于 SQL 阶段误判检查。恢复操作先验证一次获取连接超时，再释放连接并生成新的正常窗口。响应包含 `acquisitionTimeoutCount`、`queryErrorCount` 和 `recoveryVerified`。这些操作需要该服务显式开启控制权限；完整语义见[数据库连接池说明](DATABASE_POOL.md)。

仅当 `TRIAGE_OBSERVATION_SOURCE=LIVE` 时提供 `POST /api/live-lab/traffic`。同源本地页面发送 `X-Triage-Lab: 1`，请求体为 `{"service":"order-service","scenario":"NORMAL","count":5}`，也可选择 `DOWNSTREAM_TIMEOUT`；省略服务时使用默认服务。`count` 范围 1–10。目标必须显式开放 `labEnabled`，只读接入服务返回 403。接口先清空样例观测、设置场景，再向样例订单接口发出指定数量的请求，返回实际请求数、超时次数和 p95。它是显式实验控制，不属于 Agent 的只读工具。

## 仅文档对照评测

`POST /api/evaluation/document-only` 仅供本机同源评测，写请求需要 `X-Triage-Settings: 1`。请求体为 `{"question":"订单查询接口为什么变慢了？","scenario":"DOWNSTREAM_TIMEOUT","expectedSelection":"..."}`；`expectedSelection` 取自当前 `/api/config`。服务只按问题检索至多三篇排障文档，不读取实时指标和日志，也不向模型开放工具。场景只用于文档工具上下文，不作为实时事实提供给模型。

接口对当前选中的模型发起一次请求，返回 `status`、`answer`、文档 `citations`、`uncertainty`、检索到的文档 ID、模型信息、完整 usage（若服务端提供）和耗时；无效输出返回 `FAILED` 与安全的 `failureCode`，不返回原始模型消息或 API Key。该评测不写执行历史，结果由 `scripts/compare_live_methods.py` 保存在本机 `target/live-comparison/`。

明显无关的问题在仅文档入口也由应用范围门槛结束，返回 `applicationScopeGate=true`、`modelCalls=0`；正常模型调用返回 `applicationScopeGate=false`、`modelCalls=1`。两种方法的范围门槛一致，避免把这类拒绝误记成模型能力。

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

模型相关失败包括 `MODEL_HTTP_ERROR`、`MODEL_TIMEOUT`、`MODEL_ROUND_LIMIT`、`MODEL_OUTPUT_TRUNCATED`、`MODEL_RESPONSE_LIMIT`、`INVALID_MODEL_OUTPUT`、`MODEL_NO_OBSERVATIONS`、`MODEL_ASSESSMENT_MISMATCH`、`INVALID_TOOL_ARGUMENTS`、`TOOL_NOT_ALLOWED` 和 `DUPLICATE_TOOL_CALL`。`MODEL_UNSUPPORTED_TRACE_ID` 保留在旧版历史中；新版不允许模型输出正文 traceId。

## 模型执行信息

完整执行记录中的 `modelExecution` 在 DEMO 模式及旧记录中为 null。MODEL 模式包含：

| 字段 | 内容 |
|---|---|
| `configuredModel` | 配置的模型名 |
| `responseModel` | 服务端返回的模型名，未提供时为空 |
| `calls` | 已尝试的模型请求次数 |
| `usage` | 完整累计用量：inputTokens、outputTokens、totalTokens；任一轮缺失时为空 |
| `assessment` | 新版已校验的判断类型；应用门槛、失败和旧历史中为空 |

服务地址、凭据和原始模型消息不会通过接口返回。

## 模型设置接口

设置页调用以下接口。所有设置请求限于本机；写请求需要同源页面提供 `X-Triage-Settings: 1` 请求头。

| 请求 | 作用 |
|---|---|
| `GET /api/settings` | 读取服务列表和当前模式，Key 只返回 `keyConfigured` |
| `POST /api/settings/providers` | 添加服务 |
| `PUT /api/settings/providers/{id}` | 编辑服务，附带当前 version；API Key 为空时保留原值 |
| `POST /api/settings/providers/{id}/test?version=N` | 发起一次短模型请求测试连接，不切换当前服务 |
| `PUT /api/settings/selection` | 请求体为 `{"mode":"MODEL","providerId":"..."}` 或 `{"mode":"DEMO"}` |
| `DELETE /api/settings/providers/{id}?version=N` | 删除未启用的服务 |

添加、编辑字段为 `displayName`、`protocol`、`baseUrl`、`model`、`apiKey`、`temperature`、`timeoutSeconds`、`maxRounds`、`maxTokens` 和 `version`。`protocol` 可以是 `DEEPSEEK`、`DASHSCOPE`、`GLM`、`KIMI`、`LM_STUDIO` 或 `OPENAI_COMPATIBLE`。只有 DeepSeek 与 `KIMI` 下的 `kimi-k2.6` 自动发送非思考参数。页面使用 version 检查并发修改；旧版本返回 409。

`GET /api/config` 包含 `selectionToken`。排查页面提交时回传为 `expectedSelection`，防止其他页面切换模型后，旧页面在用户不知情的情况下提交给新模型。此字段不是身份认证凭据。旧 API 客户端可不传。

完整响应见[超时场景记录](examples/timeout-run.json)。
