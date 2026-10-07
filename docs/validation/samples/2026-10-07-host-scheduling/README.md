# 跨进程调度诊断短测附件

来源：[工作流 37640063390](https://github.com/MoChiUaena/agent-triage/actions/runs/37640063390)，源码 `c1355b107b26cb5983a1ba4b8e476bff90278138`，时序诊断开启，时长 60 秒。Windows/Linux 四组的 `memory.csv`、`memory-details.csv` 和 `summary.json` 按下载后的原始字节保存并通过严格重放；逐文件哈希见 [SHA256SUMS](SHA256SUMS)。

跨进程心跳在失败时只输出有界数字到工作流日志，不归档请求内容或 JVM 原始日志。解释见[诊断记录](../../2026-10-07-host-scheduling.md)。
