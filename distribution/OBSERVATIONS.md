# 接入其他 Spring Boot 应用

包内 `sdk/` 提供 0.10.0 的 Starter JAR/POM 和可选 Java Agent JAR。首次运行演示无需安装 SDK；接入自己的应用时，先在 SDK 文件所在目录安装：

```powershell
mvn org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file '-Dfile=triage-spring-boot-starter-0.10.0.jar' '-DpomFile=pom.xml'
```

业务项目依赖坐标为 `io.github.mochiuaena:triage-spring-boot-starter:0.10.0`。服务需要显式开启 Starter，设置自己的服务与下游标识、被观测的下游 HTTP origin 和业务包；Agent 在 `config/services.yml` 中登记相同身份与本机观测地址。普通上下文路径可放在登记地址后，例如 `/petclinic`。项目仍只支持本机 HTTP 地址。

新增开关默认关闭。按接口观测使用 `endpoint-observations: true` 和 Agent 的 `OBSERVATIONS_V3` 协议；开启 `response-status-counts: true` 后，页面显示固定的响应分类计数。分类只说明窗口分布，不能确认某个 404 或 5xx 的根因。旧数据未采集分类时显示未采集。

要限制同一机器上的其他进程读取观测，可设置 Starter 的 `observation-access-token`，并在对应 Agent 服务条目填写相同的 `access-token`。令牌用随机的 32–128 位字母、数字、`_` 或 `-`，放在环境变量或不提交的本地配置。轮换时，Starter 可同时接受新的当前令牌和旧的 `observation-previous-token`；先重启业务应用使两者可用，再更新并重启 Agent，最后移除旧令牌并重启业务应用。令牌不会开放非回环地址访问。

异步请求在响应完成后只计一次。MVC Callable / WebAsyncTask 可设置 `async-context-propagation: true`，同时需要接口观测；其他工作任务要在请求线程捕获快照并显式包装：

```java
import io.github.mochiuaena.triage.sdk.TriageObservationContext;

var observation = TriageObservationContext.capture();
executor.execute(observation.wrap(() -> {
    result.setResult(service.lookup());
}));
```

任务结束后恢复线程原上下文；响应已完成后的迟到调用不再写入请求观测。并发下游耗时按调用累计，可能大于请求耗时，不能当作关键路径。这里不提供任意执行器自动传播、WebFlux 或完整分布式链路。

可选 Java Agent 用于唯一加载业务类的构建摘要核对，不是接入观测的必需项。完整配置、数据字段与边界见源码仓库的 `docs/STARTER.md` 和 `docs/SERVICE_INTEGRATION.md`。默认源码只在本机检索；模型读取需要项目与本次排查授权。
