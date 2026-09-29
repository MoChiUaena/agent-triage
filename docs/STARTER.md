# Spring Boot Starter 接入

`triage-spring-boot-starter` 为同步 Spring MVC 应用提供 `/triage/observations`，复用 Agent 已有的 HTTP V1 和数据库 V2 契约。组件不依赖 Spring AI，也不读取业务日志文件。首次接入不需要模型密钥。

当前支持 JDK 21、Spring Boot 3.5 和单实例内存观测。组件尚未发布到 Maven Central，需要先在仓库根目录安装到本机 Maven 仓库：

```powershell
.\mvnw.cmd -B -ntp -f triage-spring-boot-starter/pom.xml install
```

业务项目添加依赖：

```xml
<dependency>
  <groupId>io.github.mochiuaena</groupId>
  <artifactId>triage-spring-boot-starter</artifactId>
  <version>0.2.0</version>
</dependency>
```

## HTTP 模式

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

配置开启后，过滤器记录 `/api/` 下同步请求的完成时间、耗时和生成的 traceId；`RestClientCustomizer` 记录同一请求线程内、目标 origin 匹配的下游调用。业务代码必须注入 Spring 提供的 `RestClient.Builder`，自行创建 `RestClient` 或使用其他 HTTP 客户端不会被采集。HTTP 状态码错误不会被当作超时，只有超时异常链才增加超时计数。

每个窗口中的请求数包含匹配路径下的所有同步请求；下游 p95 是每条请求内指定下游调用累计耗时的 p95，没有该调用时记为零。一次请求有多个超时仍只计一次。组件不推断正常基线，`baselineRequestP95Ms` 为 `null`。

商品请求响应中的 `X-Triage-Trace-Id` 对应 SDK 错误事件标识。SDK 不接收外部 traceId，也不会自动与业务日志已有链路关联。异步 Servlet、WebFlux、跨线程调用和多个下游暂未支持。

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

这不是透明的 JDBC 代理。直接使用 `JdbcTemplate`、JPA 或已有事务中的连接不会被自动记录；回调内不要返回依赖已关闭连接的对象。连接池大小在运行中不应修改。

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

## 数据与边界

组件默认关闭。开启后只提供本机可访问的 GET 接口；不新增任何写入或故障控制接口。请勿通过反向代理向外公开观测地址。本机其他进程仍可读取观测，因此这不是用户鉴权机制。

内存最多保留 `capacity` 条请求和有界池采样，保留时长为配置窗口加两分钟，以支持模型等待后读取提交时冻结的窗口。容量淘汰影响查询窗口时返回 422，拒绝用残缺统计生成判断；历史窗口已过保留期也会被拒绝。Agent 查询窗口不能超过组件配置。错误事件最多三条，只含固定消息、时间与随机标识，不保存 URL、参数、请求正文、头、SQL、数据库地址或原始异常。重启后观测清空，Agent 保存的执行历史不受影响。

## 验证

```powershell
.\mvnw.cmd -B -ntp -f integrations/pom.xml verify
python scripts/starter_smoke.py
```

HTTP 检查启动独立商品、库存和 Agent 进程，覆盖空窗口、正常、下游超时及请求恢复；数据库示例验证 V2 读取。Starter 测试使用真实 HikariCP/H2 检查池耗尽与阶段区分。脚本默认使用 18280、18284、18288、18289，遇到已占用端口直接退出，不关闭已有服务。全程使用固定规则，不调用付费模型。
