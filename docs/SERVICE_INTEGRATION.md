# 接入本地 Spring Boot 服务

Agent 从启动配置读取服务白名单。页面选择服务后，只读工具访问该服务的观测接口，执行记录保留当时的服务名称和下游名称。

目前支持本机 HTTP 服务，可排查请求延迟、单一下游 HTTP 超时和数据库阶段。Spring MVC 应用可使用 [Spring Boot Starter](STARTER.md) 提供观测接口；其他应用需把已有指标与错误事件转换为接入契约。没有 HTTP 下游时可选[入站请求 V4](INBOUND_HTTP.md)。Agent 不会自动解析任意 Actuator 指标或日志文件。远程地址尚未支持，观测接口可选使用共享令牌。

服务登记后，可以在“项目源码”页面绑定本机 Java 项目，把排查结果关联到代码位置，详见[源码接入](SOURCE_INTEGRATION.md)。

## 页面检查接入

启动业务应用、在 Agent 配置中登记服务后，打开“项目源码”页的“检查服务接入”。选服务和窗口，点击检查；已登记项目卡片上也有“检查接入”入口。

检查依次读取服务观测、窗口请求、源码绑定、MVC 入口与已采集错误位置，并比较可用的构建源码摘要。每项给出结果和下一步：没有请求时先访问业务接口，未绑定时登记目录，文件变动时重新索引，摘要不同时切换对应构建的源码。V1/V2 仍可读取观测和检索源码，MVC 入口及构建核对作为 V3 的可选接入项。

检查不调用模型、不创建排查历史、不生成业务请求，不会修改模型授权。它只覆盖所选窗口中观测到的入口和错误位置，不表示检查了全部接口或整个代码库。检查时源码项目发生变动会提示重新检查。

`GET /api/services/{id}/source-check?windowMinutes=5` 只检查启动配置中已登记的本机服务，不接受服务地址。省略窗口时采用不超过服务上限的最近五分钟；同时最多进行两项检查。接口只允许本机同源读取，返回步骤、索引版本、入口和位置状态，不返回代码正文、源码目录或观测地址。

## 配置服务

[examples/services.yml](../examples/services.yml) 登记了订单演示、结算接入和数据库样例。[数据库 V2 契约与启动方式](DATABASE_POOL.md)另有说明；本页下面的 JSON 为 HTTP V1 契约。配置项如下：

| 配置 | 含义 |
|---|---|
| `id`、`name` | 服务标识和页面名称，标识须为小写字母、数字及连字符 |
| `downstream-id`、`downstream-name` | 本次观测覆盖的下游；`HTTP_REQUESTS_V4` 不填写 |
| `base-url` | 带端口的本机 HTTP 地址，可有普通上下文路径，例如 `http://127.0.0.1:9966/petclinic`；不接受凭据、查询参数、编码路径或 `..` |
| `access-token` | 可选；Starter 开启观测令牌时填写相同的随机值，只由 Agent 向该服务的固定观测路由发送 |
| `protocol` | HTTP 窗口观测用 `OBSERVATIONS_V1`；按接口用 `OBSERVATIONS_V3`；只观察入站请求用 `HTTP_REQUESTS_V4`；数据库用 `DATABASE_V2`；`LAB` 保留给原有订单演示 |
| `max-window-minutes` | 允许查询的最长窗口，1–60，默认 60 |
| `lab-enabled` | 默认关闭；`LAB` 和 `DATABASE_V2` 可显式开启样例流量控制 |

配置列表会替换默认服务。修改后重启 Agent 生效。不配置列表时，原有 `TRIAGE_OBSERVATION_SOURCE` 和 `TRIAGE_OBSERVATION_BASE_URL` 继续生效。

服务列表只在服务器配置中填写。提交请求和模型工具参数只能选择登记的服务 ID，不能传入 URL；未知 ID 或超出该服务的窗口会在访问服务前被拒绝。公共配置和执行记录不包含观测地址。

设置上下文路径时，Agent 只在该前缀下拼接固定的 `/triage/observations`、`/triage/endpoint-observations`、`/triage/database-observations` 或 `/triage/request-observations` 路由，不会访问业务请求提供的路径。未登记服务时，旧 `TRIAGE_OBSERVATION_BASE_URL` 仍要求无路径的本机 origin。

V3 需要业务应用开启 Starter 的 `endpoint-observations`，查询路径为 `/triage/endpoint-observations`，窗口参数相同。可选 `endpointId` 必须来自已观测的接口列表，不能填写 URL 或原始业务 ID。V3 保留路径模板与 MVC 处理方法，按接口返回窗口指标及最多 3 条错误事件；未选择接口时返回服务窗口与最多 8 个接口摘要。方法匹配信息与完整执行轨迹有区别，范围见[Starter 说明](STARTER.md)。

当前 main 可接受 V3 的可选 `responseStatuses`，六项固定非负整数的合计必须等于窗口请求数，接口摘要的分类也必须与窗口一致。Starter 默认关闭这项扩展，需要显式设置 `response-status-counts: true`。未采集时页面显示“未采集”；旧历史保持原值。分类计数进入指标证据，路由和处理方法仍按原规则保持本机使用。4xx、5xx 或未知响应不能仅凭“没有下游超时”完成判断；针对具体状态码的问题只展示已有分布和检查建议，根因需要额外证据。

## 只读接口

接口固定为 `GET /triage/observations?windowMinutes=5&endTime=2026-09-28T00%3A00%3A00Z`，返回 `application/json`：

```json
{
  "schemaVersion": 1,
  "service": "checkout-service",
  "downstreamService": "inventory-service",
  "windowStart": "2026-09-27T23:55:00Z",
  "windowEnd": "2026-09-28T00:00:00Z",
  "requestCount": 5,
  "timeoutCount": 1,
  "recordedRequestCount": 100,
  "requestP95Ms": 310.4,
  "downstreamP95Ms": 310.0,
  "downstreamTimeoutRate": 0.2,
  "baselineRequestP95Ms": null,
  "errors": [{
    "timestamp": "2026-09-27T23:59:50Z",
    "traceId": "example-trace",
    "level": "ERROR",
    "message": "inventory request timeout"
  }],
  "synthetic": false
}
```

`service`、`downstreamService` 必须与配置一致。窗口必须严格采用请求的结束时间和分钟数，不可改为接口处理时的当前时间。计数须为非负整数，累计请求数不小于窗口请求数；超时率等于窗口超时数除以窗口请求数。耗时为非负有限数，空窗口的计数、p95 和超时率为零。

如果容量淘汰或保留期清理导致窗口缺少数据，接口必须返回 HTTP 422，不能用剩余样本返回 200。窗口起点包含在查询内，因此丢失样本的时间等于起点时也要拒绝。数据库接口同时检查业务记录和池采样的保留边界；V3 按接口筛选仍沿用整个采集器的保留边界。Agent 将 422 记录为 `OBSERVATION_WINDOW_LOST`，停止本次排查并保留已收集的证据。

正常基线未采集时用 `null`，不要填入演示数值。错误事件最多 3 条，时间均在窗口内，级别为 `ERROR` 或 `WARN`；无 traceId 时用空字符串。单条消息最多 2000 字符，整个响应不超过 32000 字节。Agent 拒绝重定向、错误版本及不符合契约的响应。

新服务无需实现 `/lab/scenario`、`/lab/reset` 或流量生成接口。其执行记录场景为 `OBSERVED`，表示读取实际观测；是否存在超时由证据决定。错误消息会进入本地执行记录，模型模式也会发送给所选模型服务，接入方应在接口中去除凭据和业务敏感字段。

## 跑通第二个服务

该示例复用 `sample-service` 可执行包，启动一个独立 JVM，以配置的身份记录实际 HTTP 请求。它用于验证接入流程，不代表另一个业务项目。

先按 README 启动库存与订单服务，并完成根项目和样例项目构建。在新终端启动结算示例：

```powershell
java -jar sample-service/target/triage-sample-service-0.11.0.jar --server.port=18092 --sample.service-id=checkout-service --sample.lab-enabled=false --sample.error-log-file=./data/checkout-errors.jsonl
```

停止原 Agent 后，带配置重新启动：

```powershell
java -jar target/agent-triage-0.11.0.jar --spring.config.additional-location=file:./examples/services.yml
```

用 PowerShell 产生实际请求：

```powershell
1..5 | ForEach-Object { Invoke-RestMethod "http://127.0.0.1:18092/api/requests/$_" }
```

打开页面，选择“结算接入示例”，查询最近 5 分钟。页面不会显示流量控制按钮；结果、指标和历史记录会使用该服务的身份。示例默认共用库存服务，因此订单演示切换下游故障场景时也会影响结算请求。需要独立下游时，可另外启动库存实例并通过 `--sample.inventory.base-url` 指定地址。

适配器实现见 [ObservationsController.java](../sample-service/src/main/java/io/github/mochiuaena/sample/ObservationsController.java)。实际项目应沿用这个接口契约，替换为自己的观测存储。
