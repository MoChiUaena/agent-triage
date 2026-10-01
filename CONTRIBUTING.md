# 贡献指南

使用 JDK 21 和仓库内的 Maven Wrapper。提交前运行：

```powershell
.\mvnw.cmd verify
```

macOS / Linux 使用 `./mvnw verify`。涉及工具参数、执行状态或证据引用的修改，请补充对应测试。默认测试应能在没有模型密钥的环境下运行。

## PostgreSQL 测试

```powershell
docker compose up -d --wait postgres
$env:TRIAGE_TEST_DB_URL = 'jdbc:postgresql://localhost:15432/triage'
$env:TRIAGE_TEST_DB_USER = 'triage'
$env:TRIAGE_TEST_DB_PASSWORD = 'triage-local-demo'
.\mvnw.cmd '-Dtest=RunApiTest' test
```

`TRIAGE_TEST_DB_*` 用于测试，应用运行时使用 `TRIAGE_DB_*`。如果修改过本地数据库密码，请同步调整测试配置。

## 提交修改

修改执行器、取消或 SDK 上下文后，可运行五分钟资源回归：

```powershell
.\mvnw.cmd -Dtest=RunResourceLifecycleTest -Dtriage.resource.seconds=300 '-DargLine=-Xms96m -Xmx256m' test
.\mvnw.cmd -f triage-spring-boot-starter/pom.xml -Dtest=ResourceLifecycleTest -Dtriage.resource.seconds=300 '-DargLine=-Xms96m -Xmx256m' test
```

不传 `triage.resource.seconds` 时，这两组检查在默认测试中执行较少轮次。测试使用独立内存 H2、可控任务和测试请求；不会读取本机模型设置或调用付费模型。Agent 的资源测试只清理自己创建的终态记录，观测样本保留有界容量。固定堆与 GC 后采样用于检查本次测试的留存边界，不代表吞吐或原生内存基准。

一个提交尽量解决一个问题。PR 中说明修改原因和验证方式；接口或配置有变化时，同步更新文档。

示例数据应保留 `synthetic` 标记。请勿提交真实业务日志、凭据、`.env` 或本地数据库文件。
