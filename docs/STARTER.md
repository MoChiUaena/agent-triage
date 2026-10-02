# Spring Boot Starter 接入

`triage-spring-boot-starter` 为 Spring MVC 应用提供只读观测，支持 HTTP V1、数据库 V2、MVC 接口 V3，以及主分支的入站请求 V4。组件不依赖 Spring AI，也不读取业务日志文件。首次接入不需要模型密钥。

源码和预览包使用 0.10.0，支持 JDK 21、Spring Boot 3.5 和单实例内存观测，尚未发布到 Maven Central。[v0.10.0 附件入口](https://github.com/MoChiUaena/agent-triage/releases/tag/v0.10.0)提供独立 Starter JAR/POM 与可选 Agent JAR，[v0.6.0](https://github.com/MoChiUaena/agent-triage/releases/tag/v0.6.0) 保留旧附件。在附件所在目录安装：

```powershell
mvn org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file '-Dfile=triage-spring-boot-starter-0.10.0.jar' '-DpomFile=triage-spring-boot-starter-0.10.0.pom'
```

也可在仓库根目录构建并安装 Starter：

```powershell
.\mvnw.cmd -B -ntp -f triage-spring-boot-starter/pom.xml install
```

已经公开的 [v0.3.0](https://github.com/MoChiUaena/agent-triage/releases/tag/v0.3.0) 和 [v0.2.0](https://github.com/MoChiUaena/agent-triage/releases/tag/v0.2.0)保留原版本附件；JPA 接入需要 v0.4.0。

业务项目添加依赖，版本与安装的 Starter 一致。当前源码使用：

```xml
<dependency>
  <groupId>io.github.mochiuaena</groupId>
  <artifactId>triage-spring-boot-starter</artifactId>
  <version>0.10.0</version>
</dependency>
```

## HTTP 模式

没有 HTTP 下游时，可选择[入站请求模式](INBOUND_HTTP.md)，省去下游占位配置。下面的 HTTP 模式仍会观察一个明确配置的下游；默认配置和旧协议保持兼容。V4 需从当前源码构建，v0.10.0 发布附件保留原功能。

```yaml
triage:
  sdk:
    enabled: true
    service-id: catalog-service
    downstream-id: inventory-service
    downstream-base-url: http://127.0.0.1:18084
    request-path-prefix: /api/
    max-window-minutes: 15
    capacity: 10000
```

配置开启后，过滤器记录 `/api/` 下请求的完成时间、耗时和生成的 traceId。Spring MVC 异步请求会等到 Servlet 完成回调后记录一次，耗时包含异步等待；最终 5xx 状态记为请求错误。`RestClientCustomizer` 记录有效请求上下文中、目标 origin 匹配的下游调用，工作线程的接入见下文。业务代码必须注入 Spring 提供的 `RestClient.Builder`，自行创建 `RestClient` 或使用其他 HTTP 客户端不会被采集。HTTP 状态码错误不会被当作下游超时，只有超时异常链才增加下游超时计数。

每个窗口中的请求数包含匹配路径下已完成的同步和 Spring MVC 异步请求；下游 p95 是每条请求内指定下游调用累计耗时的 p95，没有该调用时记为零。一次请求有多个超时仍只计一次。组件不推断正常基线，`baselineRequestP95Ms` 为 `null`。

商品请求响应中的 `X-Triage-Trace-Id` 对应 SDK 错误事件标识。SDK 不接收外部 traceId，也不会自动与业务日志已有链路关联。工作线程需要按下文显式包装任务；未包装的任务不会自动归属请求。WebFlux 和多个下游暂未支持。

## 显式包装工作线程

在仍处于请求线程时调用 `TriageObservationContext.capture()`，再用快照包装即将交给执行器的 Runnable 或 Callable。例如 DeferredResult 的生产任务：

```java
var observation = TriageObservationContext.capture();
executor.execute(observation.wrap(() -> {
    result.setResult(service.lookup());
}));
```

包装只携带内部观测上下文，不复制请求参数、请求头或正文。任务在工作线程上执行时，注入的 `RestClient.Builder` 所创建客户端可将匹配的下游调用归属到原请求；任务正常结束或抛出异常后，线程原有上下文会恢复。空快照或已完成请求的快照仍会执行任务，但不附上请求上下文。已经运行的任务在请求完成后返回时，迟到的耗时、超时和错误位置不再写入该请求，已保存的窗口不会改变。

多个任务可共享同一个请求快照。累计下游耗时是被观测调用耗时之和，可能大于并发请求的总耗时，不能当作关键路径；多个超时仍只将该请求计为一次超时，错误位置保留首次捕获的结果。

主分支在响应完成时释放上下文中的 MVC 处理类、接口和故障位置引用，已经采集的窗口仍保留对应描述。即使应用继续持有已完成的快照，也不会通过这个处理类引用阻止业务类加载器回收。包装不负责调度或取消业务任务；应用仍需关闭自己的执行器，及时归还 JDBC 连接。关闭 JDBC/JPA 观测器会停止采样线程，连接池由应用管理。

Spring MVC 的 Callable 和 WebAsyncTask 可显式开启 `triage.sdk.async-context-propagation: true`，同时需要 `endpoint-observations: true`。Starter 在 MVC 交接 Callable 时捕获上下文，在执行器线程处理任务前附上，并在任务返回或抛出异常后恢复。该开关默认关闭，也不会装饰应用的其他执行器。DeferredResult、CompletableFuture 或应用自行创建的任务仍需在请求线程捕获快照并显式包装。响应完成后，仍在运行的任务只能继续执行业务逻辑，不能继续向已完成请求添加观测。

## 只读观测接口的访问令牌

观测接口默认只接受回环地址的请求。需要限制同一机器上的其他进程时，业务应用可设置 `triage.sdk.observation-access-token`。令牌须为 32–128 位的字母、数字、`_` 或 `-`；例如用 `python -c "import secrets; print(secrets.token_urlsafe(32))"` 生成。Agent 在对应的 `triage.services` 条目中设置同一个 `access-token`，请求时通过 `X-Triage-Observation-Token` 发送。未设置令牌的旧配置继续按回环地址规则工作；令牌不会进入服务列表或排查记录。

轮换时，先让业务应用同时配置新的 `observation-access-token` 和旧的 `observation-previous-token`，重启应用；再将 Agent 的 `access-token` 改为新值并重启 Agent；最后移除旧令牌并重启业务应用。上一令牌不能单独配置。令牌应放在环境变量或被 Git 忽略的本地配置中，不要写入仓库。开启令牌不会开放非回环地址访问。

## 按接口观测

HTTP 应用可额外设置 `triage.sdk.endpoint-observations: true`，Agent 对应服务设置 `protocol: OBSERVATIONS_V3`。页面会显示窗口中实际匹配过的接口，可单独查询每个接口的请求数、超时和耗时。原 `/triage/observations` 的 V1 响应保持不变，V3 使用独立的 `/triage/endpoint-observations`，默认关闭。

V3 记录 HTTP 方法、Spring MVC 注册的路径模板、处理方法的类名、方法名与参数类型，不读取实际请求 URL、路径变量、查询参数、请求头或正文。例如请求 `/api/tickets/T-1` 只保留模板 `/api/tickets/{id}`。同一路径的不同 HTTP 方法分别统计，未匹配处理方法的请求计入未关联数量。

当前 main 可额外设置 `triage.sdk.response-status-counts: true`。服务窗口、所选接口窗口和接口摘要会包含 `responseStatuses`：`informational`、`successful`、`redirection`、`clientError`、`serverError`、`unknown`，分别对应 1xx、2xx、3xx、4xx、5xx 和未取得最终状态的请求。六项合计等于该窗口请求数。异步响应完成后才计数；异常穿过过滤器而响应尚未定稿时记为未知。组件只保存类别，不保存具体状态码或响应正文。该开关默认关闭，需同时开启接口观测并升级 Agent；旧 Agent 会拒绝新增字段。

记录来自 MVC 的处理方法选择阶段，不能证明方法体或后续调用执行过，也不生成完整的同步、跨线程或分布式执行轨迹。异步接口会在请求完成后计入最初选择的 MVC 接口。超过 8 个参数或标识长度上限的处理方法不保留描述信息。列表最多展示 8 个接口，优先展示超时较多的接口；其他接口请求数单独保留。底层请求容量和窗口限制与 V1 相同。

Agent 在本机用这些标识关联源码入口，默认模型请求会移除路由与处理方法描述，仅使用相应窗口的观测数值。源码片段读取仍需独立的项目授权和本次勾选，详见[源码接入](SOURCE_INTEGRATION.md)。

## 数据库模式

业务应用需要已有的 HikariCP `DataSource`，最大连接数在 1–64 之间。设置 `kind: DATABASE`，并以数据库标识填写 `downstream-id`；该模式不安装 HTTP 采集过滤器。

```yaml
triage:
  sdk:
    enabled: true
    kind: DATABASE
    service-id: catalog-db-service
    downstream-id: catalog-db
    max-window-minutes: 15
spring:
  datasource:
    hikari:
      maximum-pool-size: 2
```

把一次查询交给注入的 `TriageJdbcObserver`，组件负责获取和关闭连接：

```java
return observer.query(connection -> {
    try (var statement = connection.prepareStatement("SELECT price FROM products WHERE id = ?")) {
        statement.setString(1, id);
        try (var result = statement.executeQuery()) {
            return result.next() ? result.getBigDecimal(1) : null;
        }
    }
});
```

每次回调是一条观测操作，不等同于整条 HTTP 请求。获取连接耗时与回调耗时分别统计，查询耗时不含连接关闭时间；回调本身包含的其他计算也会计入查询阶段。回调异常仍按原类型向业务代码抛出，不保存异常正文。HikariCP 池满等待按 50ms 周期采样，获取连接超时与获取连接后的失败使用不同错误代码。

上述显式回调模式不会自动采集 `JdbcTemplate` 或 JPA。回调内不要返回依赖已关闭连接的对象；连接池大小在运行中不应修改。

## JPA 数据库观测

v0.4.0 可在 HTTP V3 应用中另接入一个数据库观测服务。业务仓库方法保持原样，但需要显式提供使用观测包装的 Hikari `DataSource`。两个服务共用业务应用的本机端口，分别读取 HTTP 接口和数据库阶段：

```yaml
triage:
  sdk:
    enabled: true
    service-id: petclinic-service
    downstream-id: unobserved-http
    downstream-base-url: http://127.0.0.1:1
    request-path-prefix: /owners/
    endpoint-observations: true
    jpa-observations: true
    jpa-service-id: petclinic-db-service
    jpa-database-id: petclinic-h2
```

在 Spring 应用里提供 `DataSource` Bean；常见 Spring Data JPA 仓库会使用这一 Bean：

```java
@Bean(destroyMethod = "close")
TriageJpaObserver.ObservedDataSource dataSource(DataSourceProperties properties,
                                                 TriageJpaObserver observer) throws SQLException {
    HikariDataSource pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    return observer.wrap(pool);
}
```

上例只展示包装入口。真实接入需要保留应用原有的 Hikari 配置绑定、池大小、超时与关闭顺序；`DataSourceProperties` 不会自动代替 `spring.datasource.hikari.*` 的专有设置。已有数据源时应按其装配方式包装，不能复制验收类中的单连接池故障参数。[应用接入说明](APPLICATION_ADOPTION.md)列出了需要额外修改的部分。

Agent 启动配置同时登记 `OBSERVATIONS_V3` 服务与以下数据库服务，`base-url` 使用同一本机应用 origin：

```yaml
    - id: petclinic-db-service
      name: Spring Petclinic 数据库
      downstream-id: petclinic-h2
      downstream-name: H2
      base-url: http://127.0.0.1:18471
      protocol: DATABASE_V2
      database-alias: true
      max-window-minutes: 15
```

`database-alias` 仅适用于 `DATABASE_V2`，Agent 只访问固定的 `/triage/database-observations`；原有数据库接入仍读取 `/triage/observations`。两个观测接口都只接受本机读取。不开启 `jpa-observations` 时，不创建数据库观测 Bean；开启但未接入包装池时返回不可用，不会伪造空窗口。

数据库 `requestCount` 表示被观测的 JDBC 执行次数加连接获取失败次数，可能多于 HTTP 请求数。获取连接和 `Statement.execute*` 分开计时；只记录阶段、耗时、固定错误代码和随机请求标识，不保存 SQL、参数或异常正文。获取连接超时与 SQL 执行失败保留原有 V2 错误代码和池采样规则。启动阶段、过滤器路径之外和其他线程的 JDBC 操作不计入业务窗口；`ResultSet` 遍历和直接取出底层连接后的调用暂未覆盖。原有 `TriageJdbcObserver` 显式回调模式继续可用。

[官方 Petclinic 验收](PETCLINIC.md)给出可运行配置与实际结果。其额外的测试故障入口只存在于验收配置类，Starter 不提供故障控制接口。

## 在 Agent 中登记

```yaml
triage:
  observation:
    source: LIVE
  services:
    - id: catalog-service
      name: 商品服务
      downstream-id: inventory-service
      downstream-name: 库存服务
      base-url: http://127.0.0.1:18098
      protocol: OBSERVATIONS_V1
      max-window-minutes: 15
      lab-enabled: false
```

数据库模式使用 `DATABASE_V2`，身份必须和 SDK 配置一致。Agent 配置见 [服务接入](SERVICE_INTEGRATION.md)，可运行示例见 [商品服务](../catalog-service/README.md)。无需编写观测 Controller 或故障切换接口。

## 采集错误位置

HTTP V3 可以额外记录错误发生时的业务代码位置，默认关闭。需要同时升级 Agent 和 Starter，并指定业务包：

```yaml
triage:
  sdk:
    endpoint-observations: true
    exception-locations: true
    application-packages:
      - example.helpdesk
```

通过已观测 RestClient 调用下游时，IO 或运行异常会保留失败时当前线程的业务位置；请求异常穿过过滤器，或交给 Spring MVC 的异常处理器时，读取异常栈。Starter 的 MVC 观察器只记录位置并继续交给应用原有的异常处理器。两种来源分别标识，均只保留所配置包及其子包的类、方法、Java 文件名和行号，最多 4 个异常类型与 8 个业务位置。缺少调试信息时文件名或行号为空，达到限额会标记截断。

不保存异常消息、参数、绝对路径或完整 Throwable。位置只出现在 V3 错误事件的可选 `failureLocation` 中；V1/V2 输出保持原样。没有抛出异常的 HTTP 错误状态、在进入 MVC 异常处理器前就被业务代码捕获的普通异常，以及没有观测上下文的工作线程调用不保证有位置。位置摘要不是完整调用轨迹，构建对应关系需另开下文的版本核对。

Agent 勾选本机源码检索后，可以在“代码引用”查看匹配和行号。新增位置和展开片段仅在本机展示；运行模型请求与可选源码模型请求都会移除位置摘要，不扩大已授权的候选代码范围。

## 核对构建源码摘要

HTTP V3 可设置 `triage.sdk.source-version-checks: true`，默认关闭。该设置还需要 `endpoint-observations: true`，并与新版 Agent 一起使用。没有构建清单、清单不能读取或类文件摘要不符时，不返回源码摘要，页面显示未核对。

构建项目时，Starter 中的 `SourceBuildManifest` 读取 Java 源文件和已经编译的类文件，生成 `META-INF/triage/source-digests-v1.properties`。清单只含类名及 SHA-256 摘要，不包含源码、绝对路径或构建凭据；嵌套类关联到同一源文件。语法解析不执行注解处理器。

Maven 项目可以在 `process-classes` 阶段生成清单，随后由打包步骤带入 JAR。建议使用 `clean verify` 生成干净的构建；Agent 的源码索引不会编译业务项目。

```xml
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>exec-maven-plugin</artifactId>
  <version>3.6.3</version>
  <executions>
    <execution>
      <id>record-build-sources</id>
      <phase>process-classes</phase>
      <goals><goal>java</goal></goals>
      <configuration>
        <mainClass>io.github.mochiuaena.triage.sdk.SourceBuildManifest</mainClass>
        <arguments>
          <argument>${project.basedir}/src/main/java</argument>
          <argument>${project.build.outputDirectory}</argument>
        </arguments>
      </configuration>
    </execution>
  </executions>
</plugin>
```

也可运行 `java -cp triage-spring-boot-starter-0.10.0.jar io.github.mochiuaena.triage.sdk.SourceBuildManifest 源码目录 类文件目录`，在打包前将清单放入类输出目录。当前工具接受一个源码根目录，最多 2000 个 Java 文件、20 MB 源码、10000 个类文件与 1 MB 清单；重名源文件无法唯一关联时不生成对应条目。

运行端只读取处理类所属代码来源的清单，并核验对应类资源的摘要。MVC 描述和 HTTP 失败时仍在当前线程中的业务位置可提供可选 `sourceHash`。默认不启用 Java Agent 时，普通请求异常中只有与已选 MVC 处理类的类名、加载器名和模块名一致的栈帧才使用该处理类的已核验摘要；其他类的栈帧仍标为未知。`Throwable` 栈帧本身不提供可公开读取的 `Class` 引用，因此同名且加载器名相同的复杂多加载器部署无法仅凭这项检查区分。这是已选处理类的构建对应关系，不是对整条异常栈的认证。V1/V2 输出保持原样。

当前主分支构建 Starter 时，还会生成只含运行类查询入口的 `-agent.jar`。业务应用仍需正常依赖 Starter；启动时额外传入同一次构建生成的 Agent JAR：

```sh
java -javaagent:/path/to/triage-spring-boot-starter-0.10.0-agent.jar -jar application.jar
```

这个入口不转换类，也不动态附加到进程。独立 JAR 不包含 Starter 或 Spring 类，避免系统类加载器抢先加载业务依赖。开启后，普通请求异常才会查询 JVM 已加载的类；只有类名在当前进程中唯一、栈帧的加载器名与模块名一致，且清单与实际类资源通过校验时，才给其他业务类的帧附上摘要。同名类被不同加载器重复定义、缺少构建清单或无法核验时，仍显示未知。查询只在已开启异常位置和版本核对的请求失败时进行；单实例应用的 Starter 最多自动枚举 20 次/秒，超出的异常帧保持未知，避免高频错误持续扫描全部类。v0.4.0 发布附件没有这个可选入口。

[Petclinic 连续查询记录](validation/2026-09-30-runtime-class-lookup.md)给出一次 CI 环境的耗时和限频依据；目标服务的错误量与尾延迟仍需单独测量。

页面比较的是构建清单中的源码摘要与本机索引。摘要不同会阻止采用该入口、展开调用关系和源码模型检查，运行指标诊断仍保留。摘要一致也不等于验证了 JVM 中被热替换或转换后的字节码；该清单由本地构建生成，不是签名证明。摘要仍只在本机使用，不扩展模型输入。

## 数据与边界

组件默认关闭。开启后只提供本机可访问的 GET 接口；不新增任何写入或故障控制接口。请勿通过反向代理向外公开观测地址。本机其他进程仍可读取观测，因此这不是用户鉴权机制。

内存最多保留 `capacity` 条请求和有界池采样，保留时长为配置窗口加两分钟，以支持模型等待后读取提交时冻结的窗口。容量淘汰影响查询窗口时返回 422，拒绝用残缺统计生成判断；历史窗口已过保留期也会被拒绝。Agent 查询窗口不能超过组件配置。错误事件最多三条，只含固定消息、时间与随机标识，不保存 URL、参数、请求正文、头、SQL、数据库地址或原始异常。重启后观测清空，Agent 保存的执行历史不受影响。

`capacity` 按采集记录数计算。HTTP 中所有接口共用容量，选择一个接口不会增加它的保留范围；JPA 中一次 HTTP 请求可能产生多条 JDBC 记录。刚好填满容量仍可读取，淘汰样本的时间等于窗口起点时必须拒绝；数据库池采样丢失也会拒绝数据库窗口。缩小窗口只有在起点严格晚于丢失边界时才有效，固定结束时间的旧窗口不会随新请求恢复。

按预计的最高记录速率、查询窗口和排查等待时间估算所需容量，并给突发流量留余量。例如每秒 20 条记录、15 分钟窗口，仅窗口内就需要约 18000 条；默认 10000 条不足以保留这一窗口。这是容量估算，不是吞吐或性能测试结果。主分支还会记住保留期清理的丢失边界，避免时钟回拨后把旧窗口当作完整数据；已发布的 v0.10.0 附件保持原样。

## 验证

```powershell
.\mvnw.cmd -B -ntp -f integrations/pom.xml verify
python scripts/starter_smoke.py
```

HTTP 检查启动独立商品、库存和 Agent 进程，覆盖空窗口、正常、下游超时及请求恢复；数据库示例验证 V2 读取。Starter 测试使用真实 HikariCP/H2 检查池耗尽与阶段区分。脚本默认使用 18280、18284、18288、18289，遇到已占用端口直接退出，不关闭已有服务。全程使用固定规则，不调用付费模型。
