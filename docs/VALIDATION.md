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
| `ToolsTest` | 6 | 服务与窗口参数、场景指标、日志范围、文档检索、LIVE 文档版本及观测地址限制 |
| `RunServiceTest` | 14 | 结论、证据不足、调用限制、异常与超时、重启恢复、重复请求和证据去重 |
| `RunApiTest` | 7 | HTTP 请求、参数校验、历史查询、SSE 重放、页面和模式信息 |
| `TriageApplicationTest` | 1 | 无模型凭据时启动应用 |
| `ModelIntegrationTest` | 29 | Spring AI HTTP 协议、多轮工具调用、参数与引用校验、截断、超时、错误脱敏和 usage |
| `LiveModelOutputTest` | 1 | 空请求窗口即使有三类证据也不能被模型判为成功 |
| `ModelSettingsTest` | 9 | 必填配置、地址限制、凭据脱敏和配置上限 |
| `EnvironmentSettingsTest` | 1 | 文档中的环境变量能覆盖默认配置 |
| `CredentialCipherTest` | 3 | 非重复密文、重启后可解密、跨服务不能复用密文、丢失密钥时不会重建 |
| `ProviderSettingsTest` | 18 | 页面配置存取、Key 脱敏、编辑留空保留、切换快照、百炼/GLM/Kimi 参数、兼容接口路径、连接测试和跨站写入拒绝 |
| `UnconfiguredModelStartupTest` | 1 | 环境变量没有模型 Key 时，仍可进入设置页完成配置 |

工具失败测试会注入一个抛出异常的工具，检查结果是否为 `FAILED / TOOL_ERROR`，且诊断为空。调用次数测试将上限设为 2，确认第三个工具没有执行。超时测试检查中断是否发出，以及迟到结果是否被忽略。

主项目合计 90 项，独立订单和库存样例服务各有 1 项 HTTP 集成测试。模型服务返回预设响应，用于验证协议和执行边界；模型实际选择工具的能力和结论质量尚未评测。

## 2026-09-27 运行结果

本地环境：Windows、JDK 21.0.12.1、Maven 3.9.11、Spring Boot 3.5.16。

| 检查 | 结果 |
|---|---|
| Maven verify | 主项目 90 项、订单服务 1 项、库存服务 1 项通过 |
| 环境变量隔离 | 预设 MODEL 模式和不可达模型地址后，旧的演示测试仍使用 DEMO 模式；模型测试只访问本机模拟服务 |
| PostgreSQL 16.10 | 7 项 HTTP 集成测试通过，首次迁移成功 |
| HTTP 冒烟脚本 | 超时、正常、无关问题、缺失规则 4 个案例通过 |
| 浏览器操作 | 场景切换、历史查看、引用展开正常 |
| H2 重启 | 原执行状态、9 个事件和 4 条证据保留 |
| GitHub Actions | Windows、Ubuntu、PostgreSQL、跨服务 HTTP 集成[全部通过](https://github.com/MoChiUaena/agent-triage/actions/runs/36308598815) |
| 本地模型协议冒烟 | 正常 / 超时各执行 2 轮模型请求、3 次工具调用；缺失 usage 保持 null |
| 设置页面本地验收 | 新增、测试、编辑时 Key 留空、启用模型、完成排查、切回演示模式和删除确认窗口 |
| 本地真实请求链路 | 三个独立 JVM；空窗口证据不足；5 次正常请求和 5 次库存超时请求均可排查；Micrometer 指标与 JSON 错误日志可核对；页面流量控制可用 |
| LIVE 页面 | 桌面与 390px 窄屏可生成请求并查看结果，无横向溢出 |
| LIVE + 模型协议模拟器 | 空窗口、正常和超时三条链路通过；每条 3 次工具调用、2 轮接口交互；不代表真实模型效果 |

[CI 运行列表](https://github.com/MoChiUaena/agent-triage/actions/workflows/ci.yml) · [早期演示冒烟结果](validation/2026-09-27-demo-smoke.json) · [演示超时场景记录](examples/timeout-run.json)

[本地真实请求复查结果](validation/2026-09-27-live-smoke.json)来自订单与库存两个独立样例进程，`synthetic=false`。它验证样例请求到排障结论的链路；不代表生产流量或真实模型效果。

[LIVE 模型协议结果](validation/2026-09-27-live-model-stub.json)来自固定响应的本地接口模拟器，未产生真实模型费用。

冒烟结果中的 `wallTimeMs` 包含客户端请求和轮询等待，是单次运行耗时。

20 个案例已分为调试集和留出集；演示模式调试集 10/10 通过状态与结构检查，结果见[小规模评测](EVALUATION.md)。留出集和真实模型评测尚未运行，接入方法见[模型配置](MODELS.md)。

## 模型响应截断

Spring AI 1.1.8 将服务端 `finish_reason=length` 转为大写 `LENGTH`。只按小写判断时，完整 JSON 片段可能被误当成正常结果。现在按不区分大小写的方式检查，并返回 `MODEL_OUTPUT_TRUNCATED`；集成测试包含这个响应。

## Windows 打包时提示无法重命名 JAR

用 `java -jar target/agent-triage-0.1.0-SNAPSHOT.jar` 启动后，如果再次执行 package，Spring Boot repackage 可能报错：无法将 JAR 重命名为 `.jar.original`。

原因是运行中的 Java 进程占用了该文件。停止进程后重新打包即可。开发时建议使用 `mvnw.cmd spring-boot:run`；需要同时运行和重新打包时，可从 JAR 副本启动。
