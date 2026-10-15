# 商品服务接入示例

这个应用提供商品、结算和价格查询，通过 `triage-spring-boot-starter` 提供观测接口。应用包不包含观测 Controller，也不依赖 Agent 的模型或历史存储。

从仓库根目录构建组件与示例：

```powershell
.\mvnw.cmd -B -ntp -f integrations/pom.xml verify
java -jar catalog-service/target/triage-catalog-service-0.12.0.jar
```

默认监听本机 18098。`GET /api/products/demo` 调用库存；`GET /api/checkouts/demo` 还会调用 18092 上的分配服务。先启动库存和分配服务，再产生请求，Agent 的 V5 窗口会分开显示两条依赖。库存响应延迟到 600 ms 时，结算接口在 300 ms 读时限内返回 `inventory=timeout`，分配调用仍可返回 `assignment=assigned`。

同一包可用独立 JVM 启动数据库接入示例：

```powershell
java -jar catalog-service/target/triage-catalog-service-0.12.0.jar --spring.profiles.active=database
```

该进程监听 18099，`GET /api/prices/demo` 使用预编译 SQL 查询内存 H2。查询通过 `TriageJdbcObserver.query` 执行，记录获取连接和查询阶段的耗时。没有故障切换或流量生成接口。

Agent 配置和完整验证方法见 [Starter 接入](../docs/STARTER.md)。这些都是本地业务样例，不代表生产部署或真实用户流量。
