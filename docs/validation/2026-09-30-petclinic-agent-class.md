# Petclinic 跨类异常的版本核对

2026-09-30，在主分支提交 `caff973` 上运行[公开 Petclinic 验收](https://github.com/MoChiUaena/agent-triage/actions/runs/36673151094)。业务项目仍固定在官方提交 `67643c4137eb75bfeb177b427f8459c471bdcbd8`，原有业务类没有改动。

本次从 Starter 构建出独立的 `-agent.jar`，在 Petclinic 进程启动时通过 `-javaagent` 加载。它只提供已加载类查询，不转换字节码。原有 MVC 页面请求、普通业务异常、源码版本差异和 JPA 阶段检查继续通过。

JPA 验收模式下，默认关闭的本机探针额外触发一次 `/owners/verification-class-error`。异常从辅助类 `ProbeFailure` 抛出，而 MVC 已选处理类是 `LocalDatabaseCheck`。排查结果中，`ProbeFailure` 对应帧的构建源码摘要为 `MATCHED`；这验证了处理类之外的业务帧确实取得了运行类引用，并通过构建清单与类文件校验。探针只在验收配置开启、请求来自本机且带测试请求头时可用；运行使用 `DEMO + LIVE`，模型调用为零。

单元测试还覆盖两个边界：不启用 Java Agent 时沿用 MVC 处理类的核对方式；两个加载器定义同名类时，不给该类的异常帧填写摘要。显式改写过的异常栈、运行时热替换的字节码和未包含在构建清单中的类不由本次核对证明。原始运行 JSON 写在 CI 临时目录，未上传为附件。[常规 CI](https://github.com/MoChiUaena/agent-triage/actions/runs/36673133204)覆盖 Windows、Linux、PostgreSQL 和 LIVE 链路。
