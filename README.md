# Agent Triage

[![Verify](https://github.com/MoChiUaena/agent-triage/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/MoChiUaena/agent-triage/actions/workflows/ci.yml)

Agent Triage 是一个 Java 服务排障助手，通过查询日志、指标和排障文档，分析接口变慢的可能原因，并给出验证建议。

目前支持订单服务的下游超时场景和正常状态对照。默认演示模式使用固定规则，无需模型密钥；也可以配置 Spring AI + DeepSeek，由模型选择工具并生成结论。两种模式都使用合成观测数据。

## 功能

- 查询服务指标、近期错误日志，检索 Markdown 排障文档。
- 通过 SSE 展示工具执行进度，点击结论中的引用可以查看证据原文。
- 保存执行记录，支持历史查询和事件重放。
- 概览、证据、执行记录分开查看，支持搜索历史记录。
- 限制工具调用次数和执行时间，分别处理执行失败与证据不足。
- 模型模式校验工具参数和结果引用，记录调用轮数及服务端返回的 token 用量。
- 在[模型设置页](http://127.0.0.1:18080/settings.html)添加、测试和选择模型服务；更改配置无需重启。

## 快速启动

需要 JDK 21+。仓库自带 Maven Wrapper，首次构建会下载 Maven 和依赖。

```powershell
git clone https://github.com/MoChiUaena/agent-triage.git
cd agent-triage
# 按本机 JDK 安装位置修改；已配置 JDK 21 时可跳过
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

macOS / Linux：设置好 JDK 21 后运行 `./mvnw verify`，再运行 `./mvnw spring-boot:run`。

打开 <http://127.0.0.1:18080>。默认使用 H2 文件数据库，记录保存在 `data/` 目录。按 `Ctrl+C` 停止服务。

也可以打包运行：`./mvnw package`，然后执行 `java -jar target/agent-triage-0.1.0-SNAPSHOT.jar`。Windows 下重新打包前需先停止正在运行的 JAR。

## 试一下

输入“订单查询接口为什么变慢了？”，分别运行以下两个场景：

| 场景 | 订单查询 p95 | 库存调用 p95 | 库存调用超时率 |
|---|---|---|---|
| 下游超时 | 2350ms | 2100ms | 18% |
| 正常对照 | 120ms | 45ms | 0% |

这些是演示适配器的固定数据。超时场景会提示检查库存下游调用；正常场景会提示未发现下游超时证据。点击引用可跳转到“证据”标签页查看原文，指标和日志也可以单独展开。

切换场景只影响新执行。刷新页面后，可以从“执行记录”重新打开结果。输入“写一首诗”等无关问题，会返回“证据不足”。

左侧可搜索最近的排查记录。“新建排查”会清空当前结果，保留已有记录；输入问题后也可按 `Ctrl+Enter` 提交（macOS 为 `⌘+Enter`）。窄屏下通过左上角菜单打开历史记录。

## PostgreSQL

如需使用 PostgreSQL，先通过 Docker Compose 启动数据库：

```powershell
docker compose up -d --wait postgres
$env:SPRING_PROFILES_ACTIVE = 'postgres'
.\mvnw.cmd spring-boot:run
```

默认连接本机 `15432` 端口，数据库名和用户均为 `triage`，本地演示密码为 `triage-local-demo`。可通过 `TRIAGE_DB_URL`、`TRIAGE_DB_USER`、`TRIAGE_DB_PASSWORD` 覆盖。

自定义密码需在首次初始化数据库前设置；修改环境变量不会更新已有数据卷的密码。使用 `docker compose stop` 停止数据库，清除 `SPRING_PROFILES_ACTIVE` 后可切回 H2。两种存储的历史记录相互独立。

## 模型模式

打开[模型设置页](http://127.0.0.1:18080/settings.html)，填写服务名称、Base URL、模型和 API Key，保存后可测试连接并设为当前模型。默认提供 DeepSeek 参数，也可填写本机 OpenAI 兼容服务。配置方法、调用限制和费用说明见[模型配置](docs/MODELS.md)。

模型链路已通过本地模拟服务测试，真实服务调用待配置凭据后验证。

## 测试

```powershell
.\mvnw.cmd -B -ntp verify
# 保持服务运行，在另一个终端执行；需要 Python 3.10+
python scripts/smoke.py
```

JUnit 覆盖工具参数、超时与取消、引用校验、重启恢复、HTTP 接口和 SSE 重放。冒烟脚本运行 4 个案例，将结果写入 `target/smoke/`。依赖缓存后可以使用 `./mvnw -o verify` 离线测试。

CI 在 Windows、Linux 和 PostgreSQL 环境运行，不需要模型凭据。具体覆盖范围和运行结果见[测试说明](docs/VALIDATION.md)。

## 配置

| 设置 | 默认值 | 含义 |
|---|---|---|
| `PORT` | `18080` | HTTP 端口 |
| `TRIAGE_MAX_TOOL_CALLS` | `3` | 每次执行调用上限，可配置 1–10 |
| `TRIAGE_TOOL_TIMEOUT` | `2s` | 单次工具超时，范围 1ms–10s |
| `TRIAGE_RUN_TIMEOUT` | `60s` | 整体执行超时，范围 1ms–120s |
| `SPRING_PROFILES_ACTIVE` | 未设置 | 设置 `postgres` 切换数据库 |

查询范围限于 `order-service`，时间窗口为 1–60 分钟，问题最多 200 字符。超时从提交时开始计时；数据库 I/O 和不响应中断的工具仍可能超过该时限，详见[执行与超时](docs/ARCHITECTURE.md#执行与超时)。

应用默认仅监听本机地址，尚未实现鉴权、多实例协调和记录清理。提交的问题会保存在数据库中，请勿输入敏感信息。

## 文档

- [API](docs/API.md)
- [模型配置](docs/MODELS.md)
- [架构](docs/ARCHITECTURE.md)
- [开发计划](docs/ROADMAP.md)
- [贡献指南](CONTRIBUTING.md)

项目采用 [MIT License](LICENSE)。Maven Wrapper 使用 Apache-2.0 许可证，保留原有许可证头。
