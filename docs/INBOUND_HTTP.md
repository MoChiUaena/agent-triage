# 只观察入站 HTTP 请求

没有 HTTP 下游，或暂时只想检查应用自身请求时，使用 `HTTP_REQUESTS`。这个模式记录已完成请求的耗时、可选响应分类和错误位置，不配置下游身份或地址，也不把缺少的下游指标填成零。

当前需要从主分支重新构建并安装 Agent 与 Starter。已发布的 v0.10.0 附件不包含 V4；旧 HTTP、数据库及 V3 配置继续按原样读取。

## 业务应用配置

```yaml
triage:
  sdk:
    enabled: true
    kind: HTTP_REQUESTS
    service-id: billing-service
    request-path-prefix: /api/
    max-window-minutes: 15
    capacity: 10000
    endpoint-observations: true
    response-status-counts: true
```

不要在这个模式填写 `downstream-id` 或 `downstream-base-url`，配置不一致会在启动时拒绝。仅需要服务请求窗口时，可以省略两个可选开关；需要接口选择、响应分类或错误位置时，按 [Starter](STARTER.md) 开启相应能力。JPA 可继续包装原有数据源并登记独立的数据库别名；工作线程仍按原有规则显式包装。

## Agent 服务白名单

```yaml
triage:
  observation:
    source: LIVE
  services:
    - id: billing-service
      name: 账单服务
      base-url: http://127.0.0.1:19080
      protocol: HTTP_REQUESTS_V4
      max-window-minutes: 15
```

白名单不填写下游身份或名称。修改后重启 Agent，产生业务请求，再在页面选择该服务及接口。窗口指标显示“下游观测：未采集”；旧历史中测得的零超时仍显示为零。

## V4 只读契约

请求路径为 `GET /triage/request-observations?windowMinutes=5&endTime=...`，可选 `endpointId` 来自已观测的接口列表。只允许回环客户端，访问令牌和轮换规则与其他观测接口相同。

响应使用 `schemaVersion: 4`、`kind: HTTP_REQUESTS`，包含服务身份、准确起止时间、请求计数、累计计数、请求 p95、未知基线、最多三条错误，以及接口摘要。接口摘要最多八条，其余和未关联请求分别计数。`responseStatuses` 可选；采集时六项计数合计必须等于对应请求数。

响应没有 `downstreamService`、`timeoutCount`、`downstreamP95Ms` 或 `downstreamTimeoutRate`，接口摘要也没有下游计数和耗时。Agent 对 V4 混入这些字段、错误合计、身份或时间不符的响应拒绝读取。未采集的接口下游值在执行记录中为空或省略；源码入口和构建摘要仍可用于本机核对。

容量丢失、过期窗口和时钟回拨仍遵守原有拒绝规则。应用和 Agent 都需要明确选择新模式与协议，旧路由不会为入站模式返回一个看似完整的下游窗口。

## 当前判断边界

这一步先提供入站观测。正常和错误窗口均可展示指标、日志、接口和源码，但固定规则与模型模式都不生成下游、数据库或请求失败阶段的成功判断。模型拿到入站指标与日志后，应用直接返回证据不足，不再请求它生成最终成功结论。请求执行阶段失败的判断另按路线图实现。

响应分类反映采集时能够确认的状态。异常穿过过滤器后，容器才产生最终错误页时，分类保持“未知”；不能根据客户端看到的 500 倒填成已采集 5xx。普通错误也不能单独证明 404 业务根因或数据库内部故障。

公开项目可用 `python scripts/petclinic_smoke.py --project-directory <隔离副本> --inbound` 验证；增加 `--jpa` 同时检查数据库别名。这个脚本只用于固定 Petclinic 的隔离验收，不读取本机模型配置。
