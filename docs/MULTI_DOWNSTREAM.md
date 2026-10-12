# 一个服务的多个 HTTP 下游

当前源码支持在同一个 Spring MVC 服务中区分多个出站 HTTP 依赖。以结算服务同时调用库存和支付为例，库存超时、支付未超时会形成两行独立计数。这个能力尚未包含在 v0.11.0 发布附件中，需要从同一源码版本构建 Starter 和 Agent。

业务应用启用 Starter：

```yaml
triage:
  sdk:
    enabled: true
    service-id: checkout-service
    request-path-prefix: /api/
    endpoint-observations: true
    downstreams:
      - id: inventory-service
        base-url: http://127.0.0.1:18084
      - id: payment-service
        base-url: http://127.0.0.1:18085
```

出站调用须使用 Boot 注入的 `RestClient.Builder`、`RestTemplateBuilder` 或 `WebClient.Builder` 创建客户端。Starter 按请求的 scheme、host、port 匹配下游。列表允许 1–8 项；ID 和 origin 不能重复，不能同时填写旧的 `downstream-id` / `downstream-base-url`。未匹配的地址不会计入任何下游。

Agent 在启动配置中登记同样的服务 ID 和下游 ID；名称只用于页面和诊断文案：

```yaml
triage:
  observation:
    source: LIVE
  services:
    - id: checkout-service
      name: 结算服务
      base-url: http://127.0.0.1:18092
      protocol: OBSERVATIONS_V5
      max-window-minutes: 15
      downstreams:
        - id: inventory-service
          name: 库存服务
        - id: payment-service
          name: 支付服务
```

Agent 只读取登记地址的 `GET /triage/endpoint-observations`。V5 响应沿用 V3 的窗口与接口字段，`schemaVersion` 为 `5`，`downstreamService` 为 `null`，另含 `downstreams` 数组：

```json
{
  "downstreams": [
    {"id":"inventory-service","requestCount":2,"timeoutCount":1,"downstreamP95Ms":300.0,"downstreamTimeoutRate":0.5},
    {"id":"payment-service","requestCount":3,"timeoutCount":0,"downstreamP95Ms":20.0,"downstreamTimeoutRate":0.0}
  ]
}
```

总请求数按入站请求统计；每行请求数表示调用过该下游的入站请求数。同一请求可调用多个下游，因此分项请求数不能相加当作总请求数。总超时数按有任一下游超时的入站请求统计；同一请求的两个下游都超时时，分项超时数之和可大于总超时数。每行的 p95 只使用调用过该下游的请求。超时归因来自分项计数，不从错误消息文本猜测。

页面、证据和历史保留各行数据。固定规则与模型模式只把超时指向 `timeoutCount` 大于零的下游；零超时仅代表该窗口未观察到超时，不代表依赖整体健康。旧 V1/V3 响应与历史数据仍按原结构读取。V5 配置下的旧 V1 路由返回 404。

自动检查覆盖配置校验、RestClient / RestTemplate / WebClient 归属、V5 窗口、Agent 接口校验、诊断、页面 DOM、API 与历史快照。`verification/multi-downstream-service` 还用独立 Spring Boot 应用和两个真实回环 HTTP 服务验证库存读超时、支付成功的同请求归属。尚未在第三方业务项目中完成多下游接入验收。
