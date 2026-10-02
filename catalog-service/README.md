# 商品服务接入示例

这个应用只实现商品与价格查询，通过 `triage-spring-boot-starter` 提供观测接口。应用包不包含观测 Controller，也不依赖 Agent 的模型、存储或故障切换代码。

从仓库根目录构建组件与示例：

```powershell
.\mvnw.cmd -B -ntp -f integrations/pom.xml verify
java -jar catalog-service/target/triage-catalog-service-0.11.0.jar
```

默认监听本机 18098，`GET /api/products/demo` 通过 Spring 的 `RestClient.Builder` 调用 18084 上的库存样例。需要先启动库存服务；SDK 按配置的 origin 记录请求和超时。商品接口返回 `X-Triage-Trace-Id`，可与观测错误事件核对。

同一包可用独立 JVM 启动数据库接入示例：

```powershell
java -jar catalog-service/target/triage-catalog-service-0.11.0.jar --spring.profiles.active=database
```

该进程监听 18099，`GET /api/prices/demo` 使用预编译 SQL 查询内存 H2。查询通过 `TriageJdbcObserver.query` 执行，记录获取连接和查询阶段的耗时。没有故障切换或流量生成接口。

Agent 配置和完整验证方法见 [Starter 接入](../docs/STARTER.md)。这些都是本地业务样例，不代表生产部署或真实用户流量。
