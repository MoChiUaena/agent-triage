# Petclinic 普通异常的源码版本复验

2026-09-30，在主分支提交 `ba47061` 上运行[公开 Petclinic 验收](https://github.com/MoChiUaena/agent-triage/actions/runs/36671099533)。业务项目固定为官方提交 `67643c4137eb75bfeb177b427f8459c471bdcbd8`；本次使用 `DEMO + LIVE`，没有调用模型。

| 检查 | 结果 |
|---|---|
| 原有业务请求 | `/owners/new`、`/owners/1` 正常；两次 `/owners/999999` 返回 500 |
| 异常源码 | `OwnerController.findOwner` 为 `LINE_MATCH`，lambda 为 `LAMBDA_CANDIDATE`；两者的构建源码摘要均为 `MATCHED` |
| 诊断边界 | 业务异常返回证据不足，没有被归因为下游 HTTP 超时 |
| 源码变化 | 在验收副本增加一行注释并重新索引后，新排查返回 `SOURCE_VERSION_DIFFERENT`，不展开旧版本调用关系；原历史不改写 |
| JPA 回归 | 同一次工作流中的 SQL 错误、连接获取超时、池占用和恢复检查通过 |

这些摘要来自 MVC 已选处理类的运行类引用、构建清单和类文件核验。异常栈中其他类的版本仍未知；摘要一致也不证明完整执行路径或热替换后的字节码。验收原始运行 JSON 写入 CI 临时目录，未作为附件上传。[常规 CI](https://github.com/MoChiUaena/agent-triage/actions/runs/36671084725)的 Windows、Linux、PostgreSQL 和 LIVE 集成检查也全部通过。
