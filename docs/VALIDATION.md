# 测试说明

## 运行测试

```powershell
.\mvnw.cmd -B -ntp verify
```

macOS / Linux 使用 `./mvnw -B -ntp verify`。默认测试使用内存 H2，不需要启动外部服务。

PostgreSQL 测试配置见[贡献指南](../CONTRIBUTING.md#postgresql-测试)。启动应用后，还可以运行 `python scripts/smoke.py` 检查 HTTP、SSE 和历史查询，结果保存在 `target/smoke/`。

## 测试覆盖

| 测试类 | 数量 | 覆盖内容 |
|---|---|---|
| `ToolsTest` | 4 | 服务与窗口参数、场景指标、日志范围、文档检索 |
| `RunServiceTest` | 11 | 结论、证据不足、调用限制、异常与超时、重启恢复、引用校验 |
| `RunApiTest` | 7 | HTTP 请求、参数校验、历史查询、SSE 重放、页面和模式信息 |
| `TriageApplicationTest` | 1 | 无模型凭据时启动应用 |

工具失败测试会注入一个抛出异常的工具，检查结果是否为 `FAILED / TOOL_ERROR`，且诊断为空。调用次数测试将上限设为 2，确认第三个工具没有执行。超时测试检查中断是否发出，以及迟到结果是否被忽略。

这些测试覆盖固定规则的演示流程。模型工具选择和结论质量将在接入模型后单独评测。

## 2026-09-27 运行结果

本地环境：Windows、JDK 21.0.12.1、Maven 3.9.11、Spring Boot 3.5.16。

| 检查 | 结果 |
|---|---|
| Maven verify | 23 项通过 |
| PostgreSQL 16.10 | 7 项 HTTP 集成测试通过，首次迁移成功 |
| HTTP 冒烟脚本 | 超时、正常、无关问题、缺失规则 4 个案例通过 |
| 浏览器操作 | 场景切换、历史查看、引用展开正常 |
| H2 重启 | 原执行状态、9 个事件和 4 条证据保留 |
| GitHub Actions | Windows、Ubuntu、PostgreSQL 作业通过 |

[CI 运行](https://github.com/MoChiUaena/agent-triage/actions/runs/36290979549) · [冒烟结果](validation/2026-09-27-demo-smoke.json) · [超时场景记录](examples/timeout-run.json)

冒烟结果中的 `wallTimeMs` 包含客户端请求和轮询等待，是单次运行耗时。

## Windows 打包时提示无法重命名 JAR

用 `java -jar target/agent-triage-0.1.0-SNAPSHOT.jar` 启动后，如果再次执行 package，Spring Boot repackage 可能报错：无法将 JAR 重命名为 `.jar.original`。

原因是运行中的 Java 进程占用了该文件。停止进程后重新打包即可。开发时建议使用 `mvnw.cmd spring-boot:run`；需要同时运行和重新打包时，可从 JAR 副本启动。
