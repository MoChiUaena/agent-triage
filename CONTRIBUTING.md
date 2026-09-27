# 参与开发

1. 使用 JDK 21 与仓库 Maven Wrapper；独立分支遵循 `codex/` 前缀（AI 协助分支）或清楚的功能名称。
2. 先运行 `./mvnw verify` / `.\mvnw.cmd verify`。普通测试不得调用付费模型或依赖开发者个人密钥。
3. 每次提交对应一个明确功能、修复、测试或文档变化。AI 辅助生成后仍需检查差异与实际执行结果。
4. 修改工具契约、状态流或引用逻辑时，添加能验证行为边界的测试，并更新相关文档。
5. 演示数据必须保持 synthetic 标记，不提交真实业务日志、私人问题、Token、数据库文件、`.env` 或本地配置。
6. PR 说明写明问题、变化、验证命令与限制。不得虚构生产用户、模型效果或性能收益。

PostgreSQL 集成验证可先运行 `docker compose up -d --wait postgres`，在当前终端设置 `TRIAGE_TEST_DB_URL=jdbc:postgresql://localhost:15432/triage`、`TRIAGE_TEST_DB_USER=triage`、`TRIAGE_TEST_DB_PASSWORD=triage-local-demo` 后运行 `./mvnw -Dtest=RunApiTest test`。这三个变量只用于测试；应用配置使用 `TRIAGE_DB_*`。

项目保持 Java 单体、三个只读工具、轻量原生网页；超出 v0.1 范围的功能先说明具体问题与最小实现。
