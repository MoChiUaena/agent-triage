# 阶段 A 验证记录

日期：2026-09-27。环境：Windows、JDK 21.0.12.1、Maven 3.9.11、Spring Boot 3.5.16。所有业务数据均为合成数据，未调用模型。

## 实际执行

| 验证 | 结果 |
|---|---|
| `mvnw.cmd -B -ntp verify` | 23 项测试，0 失败 / 0 错误 |
| PostgreSQL 16.10 + `-Dtest=RunApiTest test` | 7 项 HTTP 集成测试通过，Flyway 首次迁移成功 |
| `python scripts/smoke.py` | 超时、正常、无关问题、缺失规则 4 个案例符合预期 |
| 浏览器操作 | 两种场景、历史回放、点击文档引用展开原文通过；控制台未见 warn / error |
| H2 文件库重启 | 运行 `f235cf1d-a35b-4984-b195-bebbd9546cc1` 保留 SUCCEEDED、9 个事件、4 条证据 |
| GitHub Actions | Windows / Ubuntu / PostgreSQL 三组作业成功 |

可核查的 [CI 运行](https://github.com/MoChiUaena/agent-triage/actions/runs/36290979549)、[冒烟摘要](validation/2026-09-27-demo-smoke.json)、[超时场景完整证据](examples/timeout-run.json)。摘要中的 wallTimeMs 是一次本地冒烟观测，包含客户端请求和等待，不能用来宣称吞吐量或性能提升。

## 覆盖范围

4 项工具测试验证服务 / 窗口边界、正常与超时指标、日志数量及时间范围、稳定文档 ID 和无匹配结果。

11 项执行测试验证正常 / 超时、无关问题、缺失排障规则、调用上限、工具异常、取消中断、总预算、输出上限、重启恢复，以及伪造 / 重复证据 ID。

7 项真实 HTTP 测试验证两个场景与历史、无效输入、404 / 非法 UUID、列表限制、已结束任务的 SSE 重放与 Last-Event-ID、证据不足、无密钥页面和模式元数据。另有 1 项应用启动测试。

## 实际失败案例：Windows 运行 JAR 导致重新打包失败

触发：用 `java -jar target/agent-triage-0.1.0-SNAPSHOT.jar` 启动应用后，在同一目录执行 `mvnw.cmd -DskipTests package`。

实际错误：Spring Boot repackage 无法将正在使用的 JAR 重命名为 `.jar.original`，构建失败。这是 Windows 文件占用问题，不是代码编译或测试失败。

处理：停止本项目旧 Java 进程后重新 package，构建成功。演示进程改为从 `target/demo-runtime.jar` 副本启动，再通过 HTTP 验证原执行记录完整保留。开发时优先使用 `mvnw.cmd spring-boot:run`，避免占用待打包产物。记录失败原因比隐去失败、只展示成功更有助于复现。

## 边界失败：执行失败不能伪装成证据不足

JUnit 在真实执行器中注入一个抛出异常的工具，实际得到 `FAILED / TOOL_ERROR`，事件包含 TOOL_FAILED 和 RUN_FAILED，诊断为空；断言持久化记录不含异常中的测试秘密值。另一项测试将工具调用上限设为 2，第三个工具不得执行，返回 `TOOL_CALL_LIMIT`。这些是离线故障注入，不是生产故障记录。

对照：`查看健康状态` + 超时场景可成功读取指标 / 日志，但关键词检索只命中正常规则，因此为 `INSUFFICIENT_EVIDENCE`，保留观察、不编造原因。

## 尚未验证

真实模型、模型 token / 费用、工具自主选择、提示注入场景、语义级引用正确性、20 案例评测、真实业务服务、压力测试、多实例恢复、长时间数据增长。阶段 A 结果不能代替这些验证。
