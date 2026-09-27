# Agent Triage

面向 Java 开发者的故障排查助手：结合排障文档、服务指标和错误日志，给出可核查的判断与下一步验证建议。

**正在实现阶段 A：确定性演示骨架。所有业务数据均为合成数据，当前不调用真实模型。**

## 快速启动

需要 JDK 21+。首次构建需要联网下载 Maven 和依赖，之后演示不需要模型密钥。

```powershell
# 仅影响当前终端，按实际 JDK 安装位置修改
$env:JAVA_HOME = 'C:\Users\你的用户名\java\jdk-21'
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

macOS / Linux：`./mvnw verify`，然后 `./mvnw spring-boot:run`。

打开 <http://localhost:18080>。默认使用 `data/` 下的 H2 文件数据库。

## 开发计划

见 [整体计划与验收清单](docs/ROADMAP.md)。Java 21 / Spring Boot 3.5.16 / Maven 3.9.11；Spring AI 1.1.8 BOM 已固定，模型接入属于阶段 B，当前未实现。

许可证：MIT。Apache Maven Wrapper 脚本保留其 Apache-2.0 许可证头。
