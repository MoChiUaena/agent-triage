# RestTemplateBuilder 接入验收

日期：2026-10-02。环境：JDK 21、Spring Boot 3.5.16、Spring Framework 6.2.19。验证源码：`e60f1a68ddc26aa4a03ab7338d60d1b45bcdc0b3`。

## 接入范围

HTTP 模式下，注入 Boot 的 `RestTemplateBuilder` 创建客户端即可使用已有的下游观测配置。它与 `RestClient.Builder` 共用拦截器，记录有效请求上下文内、匹配 origin 的调用耗时、已识别的超时和可选故障位置。应用的请求工厂、超时、拦截器及错误处理器保持原样。

## 验证结果

本机执行：

```powershell
.\mvnw.cmd -B -ntp -f integrations/pom.xml verify
```

Starter 99 项测试、catalog 模块 2 项测试全部通过；工单和派单模块构建通过。新增验收使用回环地址和临时端口，没有调用模型或外部业务服务。

| 场景 | 结果 |
| --- | --- |
| Boot builder 正常请求、响应头与正文等待 | 指定下游耗时进入请求窗口 |
| 带超时类型的异常及其包装链 | 记录一次超时，原异常和响应关闭行为保留 |
| 获取正文时失败、正文读取中的运行时包装异常 | RestClient 与 RestTemplate 都能记录 |
| Boot 默认 JDK 工厂的真实响应头超时 | 计入下游超时 |
| Simple 工厂的真实响应头、正文 socket 超时 | 计入下游超时；builder 的 250 ms 超时有效 |
| 普通 503、自定义错误处理器 | 不算下游超时；默认异常或应用返回行为保留 |
| 多次调用、重复定制 | 入站请求及超时只计一次；观测拦截器不重复追加 |
| 未开启、HTTP_REQUESTS、手动客户端、无上下文、其他 host、请求前缀外 | 不产生下游观测 |
| 显式包装的工作线程、任务抛错 | 归属原请求，结束后恢复线程原上下文 |
| 未包装任务、请求完成后的 execute/正文超时 | 不改变原请求窗口 |

独立 Spring Boot 验收应用位于测试包 `example.template`，不扫描商品应用的组件。JUnit 启动真实 Servlet 服务，业务接口使用 Boot 注入的 builder 和显式 Simple 工厂访问临时下游。正常请求与响应头、正文超时经 `/triage/observations` 和 `/triage/endpoint-observations` 查询，得到 3 个入站请求、2 个超时；接口模板、故障位置和响应 traceId 能对应。实际 URL、查询值、正文和异常消息没有进入观测输出。

跨平台 CI 也已通过，以下三组运行对应源码 `e60f1a68ddc26aa4a03ab7338d60d1b45bcdc0b3`：

- [常规与演示集成 37018664857](https://github.com/MoChiUaena/agent-triage/actions/runs/37018664857)：Windows/Linux 离线测试与独立应用、PostgreSQL、演示和本地模型协议桩。
- [容量检查 37018664607](https://github.com/MoChiUaena/agent-triage/actions/runs/37018664607)：Windows/Linux 两条边界检查。
- [五分钟资源检查 37018664606](https://github.com/MoChiUaena/agent-triage/actions/runs/37018664606)：Agent/Starter × Windows/Linux 四条受控流程。这里只复查组件资源门槛，不代表 RestTemplate 生产吞吐或长周期稳定性。

独立应用随后增加了最多等待 5 秒的完成记录查询；每次重试使用新的窗口结束时间，避免响应接收早于过滤器完成回调的竞态。补充后本机两项应用测试仍通过。

## 仍有的边界

Spring 6.2.19 的 JDK 工厂在正文读超时到期时会关闭输入流。本机复现得到 `RestClientException → IOException → IOException → IOException`，没有 `SocketTimeoutException` 或 `HttpTimeoutException`。这条路径能记录等待耗时，但下游超时计数保持零；若应用最终响应 5xx，仍作为请求错误记录。普通 IOException 即使消息写着“Read timed out”也不会被推断为超时。框架实现见 [JdkClientHttpRequest](https://github.com/spring-projects/spring-framework/blob/v6.2.19/spring-web/src/main/java/org/springframework/http/client/JdkClientHttpRequest.java)。

连接超时只验证了异常类型与包装链，没有模拟真实网络连接黑洞。测试也不覆盖生产吞吐、跨实例链路、WebClient、Feign、任意 JDK HttpClient 或手动 new 出来的客户端。本次源码改动尚未进入 v0.11.0 的已发布附件。

## 测试中发现的问题

最初两项 builder 测试分别得到下游耗时零、超时数零，接入后通过。扩展测试又复现了获取正文时抛错和运行时包装超时的漏记，已在共用拦截器中修正。

两项真实正文测试最初误以为一定抛出 ResourceAccessException，后来按 Spring 正文转换器的实际 RestClientException 包装修正。JDK 正文超时没有可识别类型的结果保留在上方限制中，没有用消息文本补判。

独立应用首次与原数据库测试共享 H2 内存库，重复创建 products 表导致整组失败。夹具改用独立数据库并关闭无关 SQL 初始化后，两个测试和整组构建均通过。
