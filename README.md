# Agent Triage

[![Verify](https://github.com/MoChiUaena/agent-triage/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/MoChiUaena/agent-triage/actions/workflows/ci.yml)

**先找证据，再下判断。** 面向 Java 开发者的故障排查助手：结合排障文档、服务指标和错误日志，给出可核查的判断与下一步验证建议。

> “订单查询接口为什么变慢了？请结合日志、指标和排障文档给出建议。”

阶段 A 已完成：一个可以启动、操作和测试的确定性演示。**当前使用固定规则，不调用大模型；所有业务观测均为合成数据，不代表生产诊断或模型效果。** Spring AI 真实工具调用将在阶段 B 接入，完整 v0.1 尚未发布。

## 现在可以体验什么

- 下游超时 / 正常对照两种场景，每次执行冻结场景与时间窗口。
- 三个只读工具：关键词检索 Markdown 排障文档、读取服务指标、查询错误日志。
- SSE 工具事件、带引用的观察与可能原因、下一步验证、不确定性说明。
- 点击引用跳转并展开证据原文；历史记录持久化，重启后可继续查看。
- 工具调用数、超时、并发与队列上限；执行失败和证据不足分别呈现。

## 快速启动

需要 JDK 21+；无需预装 Maven、Node、Docker 或模型密钥。首次构建需联网下载 Maven 与依赖。

```powershell
git clone https://github.com/MoChiUaena/agent-triage.git
cd agent-triage
# 若全局 JAVA_HOME 不是 JDK 21，仅为当前终端设置，按本机路径修改
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

macOS / Linux：设置好 JDK 21 后运行 `./mvnw verify`，再运行 `./mvnw spring-boot:run`。

打开 <http://127.0.0.1:18080>。默认监听本机回环地址；H2 数据保存在项目 `data/` 目录中，已排除提交。结束时在运行终端按 `Ctrl+C`。依赖已缓存后可用 `./mvnw -o verify` 离线验证。

如果需要独立 JAR：`./mvnw package` 后执行 `java -jar target/agent-triage-0.1.0-SNAPSHOT.jar`。Windows 下重新打包前先停止这个 JAR，详见[真实失败记录](docs/VALIDATION.md)。

## 三分钟演示

1. 保留默认问题，选择“下游超时”，点击“开始排查”。观察三个工具都返回证据。
2. 查看订单 p95 `2350ms`、库存下游 p95 `2100ms`、超时率 `18%`；点击引用检查对应指标、日志和文档。
3. 切换“正常对照”再次执行。指标变为 `120ms / 45ms / 0%`，结论应为“本次窗口未发现下游超时证据”。
4. 输入“写一首诗”，应得到“证据不足”，且没有工具调用。
5. 刷新页面，从“执行记录”重开之前的结果。停止服务并按同一命令重启，记录仍保留。

场景选择只影响新执行，不改写已有记录。这里的延迟数字是合成适配器返回的固定值，不是真实发压测得的数据。

## PostgreSQL

默认 H2 便于直接运行；需要 PostgreSQL 时使用同一个 JDBC 仓库和 Flyway 迁移：

```powershell
docker compose up -d --wait postgres
$env:SPRING_PROFILES_ACTIVE = 'postgres'
.\mvnw.cmd spring-boot:run
```

数据库映射到本机 `15432` 端口；数据库名和用户均为 `triage`。默认密码 `triage-local-demo` 仅是明确公开的本地演示配置。可用 `TRIAGE_DB_URL`、`TRIAGE_DB_USER`、`TRIAGE_DB_PASSWORD` 覆盖。设置自定义密码时应在初始化 Compose 数据库之前设置；已有数据卷的数据库密码不会随环境变量自动变更。退出此模式可清除 `SPRING_PROFILES_ACTIVE`，使用 `docker compose stop` 停止本项目数据库。两个存储模式的历史记录相互独立。

当前不需要向量检索，也没有启用 pgvector。

## 测试与实际验证

```powershell
.\mvnw.cmd -B -ntp verify
# 另开终端、保持演示服务运行；Python 3.10+，不安装额外包
python scripts/smoke.py
```

JUnit 目前 23 项，覆盖工具参数、场景、调用预算、工具失败与取消、总时长、引用、重启恢复、HTTP 和 SSE 重放。冒烟脚本覆盖 4 个案例，把实际运行记录写入 `target/smoke/`。它是确定性模式的功能检查，不是阶段 C 的模型评测。

GitHub Actions 在 Windows / Linux 上运行离线测试，并在 PostgreSQL 上运行 7 项 HTTP 集成测试。没有模型服务依赖，不触发付费模型请求。

[验证记录及原始结果](docs/VALIDATION.md) · [接口契约](docs/API.md) · [架构与设计取舍](docs/ARCHITECTURE.md)

## 配置与边界

| 设置 | 默认值 | 含义 |
|---|---|---|
| `PORT` | `18080` | HTTP 端口 |
| `TRIAGE_MAX_TOOL_CALLS` | `3` | 每次执行调用上限，可配置 1–10 |
| `TRIAGE_TOOL_TIMEOUT` | `2s` | 单次工具等待预算，范围 1ms–10s |
| `TRIAGE_RUN_TIMEOUT` | `10s` | 整体执行预算，范围 1ms–20s |
| `SPRING_PROFILES_ACTIVE` | 未设置 | 设置 `postgres` 切换数据库 |

预算涵盖排队时间，调度和工具边界检查期限；它不是对数据库 I/O 或任意不响应中断的 Java 代码的硬实时终止保证。应用仅允许 `order-service`、1–60 分钟窗口和不超过 200 字符的问题。历史列表最多返回 50 条。

当前没有登录鉴权、多实例协调、数据保留策略、真实监控平台适配或自动修复能力。数据库保存用户问题；请只输入合成排障问题。只有本地演示环境经过验证。

## 计划与贡献

[整体计划与验收清单](docs/ROADMAP.md)：A 确定性演示 → B Spring AI 单 Agent → C 20 案例评测 → D v0.1 发布。Java 21 / Spring Boot 3.5.16 / Maven 3.9.11；Spring AI 1.1.8 BOM 已固定，当前没有运行时模型依赖。下一阶段再提供模型服务、环境变量与费用配置说明。

项目由 AI 辅助实现，代码和验证记录可审查；不声称真实用户、生产部署或未经测量的性能提升。贡献前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

许可证：MIT。Apache Maven Wrapper 脚本保留其 Apache-2.0 许可证头；其他依赖遵循各自许可证。
