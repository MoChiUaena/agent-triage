# 测试说明

## 运行测试

```powershell
.\mvnw.cmd -B -ntp verify
```

macOS / Linux 使用 `./mvnw -B -ntp verify`。测试使用内存 H2 和本机 HTTP 模拟服务，不需要启动外部服务。应用测试显式使用 DEMO 模式，模型集成测试使用本地地址和测试凭据，不受终端中的模型模式设置影响。

PostgreSQL 测试配置见[贡献指南](../CONTRIBUTING.md#postgresql-测试)。启动应用后，还可以运行 `python scripts/smoke.py` 检查 HTTP、SSE 和历史查询，结果保存在 `target/smoke/`。

## 测试覆盖

| 测试类 | 数量 | 覆盖内容 |
|---|---|---|
| `ToolsTest` | 4 | 服务与窗口参数、场景指标、日志范围、文档检索 |
| `RunServiceTest` | 14 | 结论、证据不足、调用限制、异常与超时、重启恢复、重复请求和证据去重 |
| `RunApiTest` | 7 | HTTP 请求、参数校验、历史查询、SSE 重放、页面和模式信息 |
| `TriageApplicationTest` | 1 | 无模型凭据时启动应用 |
| `ModelIntegrationTest` | 29 | Spring AI HTTP 协议、多轮工具调用、参数与引用校验、截断、超时、错误脱敏和 usage |
| `ModelSettingsTest` | 9 | 必填配置、地址限制、凭据脱敏和配置上限 |
| `EnvironmentSettingsTest` | 1 | 文档中的环境变量能覆盖默认配置 |

工具失败测试会注入一个抛出异常的工具，检查结果是否为 `FAILED / TOOL_ERROR`，且诊断为空。调用次数测试将上限设为 2，确认第三个工具没有执行。超时测试检查中断是否发出，以及迟到结果是否被忽略。

合计 65 项。模型服务返回预设响应，用于验证协议和执行边界；模型实际选择工具的能力和结论质量尚未评测。

## 2026-09-27 运行结果

本地环境：Windows、JDK 21.0.12.1、Maven 3.9.11、Spring Boot 3.5.16。

| 检查 | 结果 |
|---|---|
| Maven verify | 65 项通过 |
| 环境变量隔离 | 预设 MODEL 模式和不可达模型地址后，65 项测试仍通过 |
| PostgreSQL 16.10 | 7 项 HTTP 集成测试通过，首次迁移成功 |
| HTTP 冒烟脚本 | 超时、正常、无关问题、缺失规则 4 个案例通过 |
| 浏览器操作 | 场景切换、历史查看、引用展开正常 |
| H2 重启 | 原执行状态、9 个事件和 4 条证据保留 |
| GitHub Actions | Windows、Ubuntu、PostgreSQL 作业通过 |
| 本地模型协议冒烟 | 正常 / 超时各执行 2 轮模型请求、3 次工具调用；缺失 usage 保持 null |

[CI 运行列表](https://github.com/MoChiUaena/agent-triage/actions/workflows/ci.yml) · [早期演示冒烟结果](validation/2026-09-27-demo-smoke.json) · [演示超时场景记录](examples/timeout-run.json)

冒烟结果中的 `wallTimeMs` 包含客户端请求和轮询等待，是单次运行耗时。

真实 DeepSeek 调用和 20 案例评测仍待完成。接入方法见[模型配置](MODELS.md)。

## 模型响应截断

Spring AI 1.1.8 将服务端 `finish_reason=length` 转为大写 `LENGTH`。只按小写判断时，完整 JSON 片段可能被误当成正常结果。现在按不区分大小写的方式检查，并返回 `MODEL_OUTPUT_TRUNCATED`；集成测试包含这个响应。

## Windows 打包时提示无法重命名 JAR

用 `java -jar target/agent-triage-0.1.0-SNAPSHOT.jar` 启动后，如果再次执行 package，Spring Boot repackage 可能报错：无法将 JAR 重命名为 `.jar.original`。

原因是运行中的 Java 进程占用了该文件。停止进程后重新打包即可。开发时建议使用 `mvnw.cmd spring-boot:run`；需要同时运行和重新打包时，可从 JAR 副本启动。
