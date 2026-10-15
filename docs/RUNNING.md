# 运行指南

首次启动见 [README](../README.md#快速开始)。本文补充源码打包、真实请求样例、数据库和运行配置。公开演示包的启动与升级方法见 [v0.11.0 说明](releases/v0.11.0.md)。

## 环境要求

- JDK 21 或更高版本；使用 `java -version` 检查当前版本。
- 从源码运行需要 Git，仓库自带 Maven Wrapper。
- 下载演示包后无需 Maven；macOS / Linux 需要 Bash 和 curl。
- PostgreSQL 为可选项，默认使用 H2 文件数据库。

Windows 可在当前 PowerShell 中设置项目使用的 JDK，不必修改系统配置：

```powershell
# 替换为本机实际的 JDK 21 安装目录
$env:JAVA_HOME = 'C:\path\to\jdk-21'
```

macOS / Linux 请确认 `JAVA_HOME` 指向 JDK 21+。首次构建需下载 Maven 和依赖，已有依赖缓存后可使用 `./mvnw -o verify` 离线测试。

## 打包运行

在仓库根目录执行：

```powershell
.\mvnw.cmd package
java -jar target/agent-triage-0.12.0.jar
```

macOS / Linux 将 `.\mvnw.cmd` 换为 `./mvnw`。打开 <http://127.0.0.1:18080>，按 `Ctrl+C` 停止。后续版本的 JAR 名称以根项目 `pom.xml` 为准；Windows 重新打包前需停止正在运行的 JAR。

默认 H2 数据库写入 `data/`，模型加密密钥位于 `data/model-config.key`。升级或迁移时需一起备份数据库和同一份密钥，已有模型配置可能仍保持启用。

## 跑通真实请求

源码启动默认使用 `SYNTHETIC` 数据。要读取本机真实请求，先在两个终端分别启动库存和订单样例。

终端一，启动库存服务：

```powershell
.\mvnw.cmd -f inventory-service/pom.xml verify
java -jar inventory-service/target/triage-inventory-service-0.11.0.jar
```

终端二，启动订单服务：

```powershell
.\mvnw.cmd -f sample-service/pom.xml verify
java -jar sample-service/target/triage-sample-service-0.11.0.jar
```

终端三，启动 Agent：

```powershell
$env:TRIAGE_OBSERVATION_SOURCE = 'LIVE'
.\mvnw.cmd spring-boot:run
```

macOS / Linux 使用 `./mvnw`，第三个终端使用：

```bash
TRIAGE_OBSERVATION_SOURCE=LIVE ./mvnw spring-boot:run
```

打开 <http://127.0.0.1:18080>，点击“生成正常请求”或“触发库存超时”，再开始排查。订单、库存服务分别监听本机 `18082`、`18084` 端口。页面显示窗口请求数、耗时、超时率和错误事件。

规则模式也能读取 `LIVE` 数据，无需模型密钥。完整链路和可核对的观测端点见[本地请求链路](LIVE_LAB.md)。

## PostgreSQL

默认 H2 无需额外安装。需要 PostgreSQL 时，可用仓库内的 Docker Compose 配置：

```powershell
docker compose up -d --wait postgres
$env:SPRING_PROFILES_ACTIVE = 'postgres'
.\mvnw.cmd spring-boot:run
```

macOS / Linux：

```bash
docker compose up -d --wait postgres
SPRING_PROFILES_ACTIVE=postgres ./mvnw spring-boot:run
```

默认连接本机 `15432` 端口，数据库名和用户均为 `triage`，本地演示密码为 `triage-local-demo`。可通过 `TRIAGE_DB_URL`、`TRIAGE_DB_USER`、`TRIAGE_DB_PASSWORD` 覆盖。自定义密码需在首次初始化数据库前设置；修改环境变量不会更新已有数据卷的密码。

使用 `docker compose stop` 停止数据库，清除 `SPRING_PROFILES_ACTIVE` 后可切回 H2。两种存储的历史记录相互独立。

## 常用配置

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `PORT` | `18080` | Agent HTTP 端口 |
| `TRIAGE_OBSERVATION_SOURCE` | `SYNTHETIC` | 设置 `LIVE` 读取已登记服务的真实观测 |
| `TRIAGE_MAX_TOOL_CALLS` | `3` | 单次排查工具调用上限，范围 1–10 |
| `TRIAGE_TOOL_TIMEOUT` | `2s` | 单次工具超时，范围 1ms–10s |
| `TRIAGE_RUN_TIMEOUT` | `60s` | 整体执行超时，范围 1ms–120s |
| `SPRING_PROFILES_ACTIVE` | 未设置 | 设置 `postgres` 使用 PostgreSQL |

应用默认仅监听 `127.0.0.1`。服务名单与观测配置见[接入说明](SERVICE_INTEGRATION.md)，模型配置优先使用网页中的“模型设置”。

排查问题和结果会保存在本机数据库中。历史页可以预览并清理已结束记录；时间限制与数据库 I/O 的关系见[执行与超时](ARCHITECTURE.md#执行与超时)。
