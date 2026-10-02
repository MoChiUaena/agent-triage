# 接入其他 Spring Boot 应用

包内 `sdk/` 提供 0.11.0 的 Starter JAR/POM 和可选 Java Agent JAR。演示无需安装 SDK；接入自己的应用时，先在 SDK 文件所在目录安装：

```powershell
mvn org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file '-Dfile=triage-spring-boot-starter-0.11.0.jar' '-DpomFile=pom.xml'
```

业务项目依赖坐标为 `io.github.mochiuaena:triage-spring-boot-starter:0.11.0`。观测和 Agent 服务地址只支持本机 HTTP；普通上下文路径可以放在登记地址后，例如 `/petclinic`。

## 只观察入站请求

应用没有 HTTP 下游，或暂时只排查请求处理过程时，在业务应用配置：

```yaml
triage:
  sdk:
    enabled: true
    kind: HTTP_REQUESTS
    service-id: billing-service
    request-path-prefix: /api/
    max-window-minutes: 15
    endpoint-observations: true
    response-status-counts: true
    request-failure-counts: true
```

在 Agent 的 `config/services.yml` 登记：

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

两边的服务标识须一致。入站模式不填写下游身份或 origin，页面显示下游未采集。修改配置后重启，产生请求，再选择服务和接口排查。

`endpoint-observations`、`response-status-counts` 和 `request-failure-counts` 都是默认关闭的可选项。失败分类要求入站模式和响应分类同时启用；同窗口计数、响应、错误事件与规则一致时，可以确认请求执行阶段异常。单纯 5xx、异步超时、已处理异常不能证明具体业务、下游或数据库内部根因。未知响应不会按客户端收到的最终 500 倒填。

## 原有 HTTP 和数据库观测

观察一个下游 origin 时，使用旧的 HTTP 模式，填写服务与下游标识、下游 HTTP origin 和业务包。按接口观测使用 `endpoint-observations: true` 和 Agent 的 `OBSERVATIONS_V3`；可选的响应分类只说明请求分布。旧 V1/V2/V3 配置继续有效，旧记录未采集的字段不会补零。

JPA 需要显式包装业务数据源，并在 Agent 登记独立数据库别名；入站请求和数据库侧分别判断。HTTP 的 503 不能替代数据库查询失败证据。其他客户端和多数据源仍需另行验证接入。

请求或池采样丢失、过期清理后读取旧窗口，会返回 422。遇到这个结果应重新选择完整窗口；系统时钟回拨也不会恢复已丢失样本。

## 访问令牌与异步任务

限制同一机器上的其他进程读取观测时，设置 Starter 的 `observation-access-token`，并在 Agent 服务条目填写相同的 `access-token`。令牌使用随机的 32–128 位字母、数字、`_` 或 `-`，放在环境变量或不提交的本地配置。轮换时，Starter 同时接受当前令牌和 `observation-previous-token`：先重启业务应用，再更新并重启 Agent，最后移除旧令牌。令牌不会开放非回环地址访问。

MVC Callable / WebAsyncTask 可开启 `async-context-propagation: true`，需要接口观测。其他工作任务要在请求线程捕获快照并显式包装：

```java
import io.github.mochiuaena.triage.sdk.TriageObservationContext;

var observation = TriageObservationContext.capture();
executor.execute(observation.wrap(() -> {
    result.setResult(service.lookup());
}));
```

请求完成后只计一次，迟到调用不再写入观测，已完成快照释放业务处理类引用。任务结束后恢复线程原上下文。并发下游耗时按调用累计，可能大于请求耗时，不能当作关键路径。任意执行器自动传播、WebFlux 和完整分布式链路不在当前覆盖范围内。

可选 Java Agent 用于唯一加载业务类的构建摘要核对。完整配置和字段见源码仓库的 `docs/STARTER.md`、`docs/INBOUND_HTTP.md`、`docs/REQUEST_EXECUTION.md` 与 `docs/SERVICE_INTEGRATION.md`。默认源码只在本机检索，模型读取需要项目与本次排查授权。
