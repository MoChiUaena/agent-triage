# 请求执行阶段异常

当入站请求处理路径中发生异常，并且窗口计数、响应分类、错误事件和对应规则一致时，可以确认“本窗口请求执行阶段发生异常”。这个判断不说明具体业务条件、下游、网络或数据库内部根因。

当前功能在主分支源码中，v0.10.0 发布附件仍保留旧行为。

## 开启采集

在[入站 V4](INBOUND_HTTP.md)配置基础上增加：

```yaml
triage:
  sdk:
    kind: HTTP_REQUESTS
    endpoint-observations: true
    response-status-counts: true
    request-failure-counts: true
```

新开关默认关闭，并要求入站模式和响应分类。异常位置和源码摘要仍分别开启；计数不依赖异常正文或堆栈采集，采集器也不保存原始异常对象。

## 分类含义

V4 窗口及接口摘要增加可选的 `requestFailures`。未开启或旧接口未提供时保持未采集，不补零。

| 计数 | 对应错误码 | 含义 |
|---|---|---|
| `executionFailures` | `REQUEST_EXECUTION_FAILED` | 请求路径有异常，采集时响应为 5xx 或未知 |
| `serverErrorResponses` | `HTTP_SERVER_ERROR_RESPONSE` | 仅确认返回 5xx，没有确认执行异常 |
| `asyncTimeouts` | `ASYNC_REQUEST_TIMEOUT` | Servlet 异步请求超时 |
| `asyncErrors` | `ASYNC_REQUEST_ERROR` | Servlet 异步生命周期报告错误 |
| `handledExceptions` | `REQUEST_EXCEPTION_HANDLED` | 请求异常已转为已知非 5xx 响应，如按业务规则返回 404 |

一次请求只进入一种分类，异步超时或错误优先单独记录。响应完成后的迟到信号不改变已有分类。错误事件保留固定码、固定消息、响应类、时间和内部 traceId，最多三条；已处理异常使用 WARN，其他分类使用 ERROR。

响应类 `0` 表示未知，`1..5` 对应各百位分类。异常穿过过滤器后，容器才产生最终 500 时仍保留未知，不倒填为已采集 5xx。判断使用的是已采集的异常阶段，不能把未知响应解释成客户端没有收到错误。

## 判断门槛

固定规则与模型共用以下要求：

- 指标和日志来自同服务、同起止时间和同请求计数，五项分类一致。
- 执行异常计数大于零，有对应的 5xx 或未知响应。
- 本窗口错误事件包含 `REQUEST_EXECUTION_FAILED`，响应类为 5 或未知。
- 本次已收集并引用 `DOC-REQUEST-EXECUTION-FAILURE#v1` 规则，同时引用指标和日志。

仅有 5xx、异步超时／错误、已处理异常、旧版普通错误或源码位置时保持证据不足。正常窗口也不据此声明整个应用健康。分类合计、接口汇总、错误码或响应类矛盾会在读取阶段拒绝，不进入模型判断。

模型只提交 `REQUEST_EXECUTION_FAILURE_OBSERVED`、证据 ID 和允许的检查项。应用再次核对门槛并生成措辞；请求执行异常不能选择旧的下游或数据库判断，旧协议也不能选择这一新判断。

## 核查结果

先用错误事件的 traceId 和时间对照应用记录，再检查本机异常位置、处理方法和业务错误处理。构建摘要不同仍停止采用不一致源码；静态调用关系不代表完整执行轨迹。

公开项目脚本可在固定隔离副本中增加 `--inbound --request-failures`，与 `--jpa` 组合时数据库阶段继续独立检查。完整混合故障、更多客户端和长周期稳定性另行验收。
