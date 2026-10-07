# HTTP 内存分项核对

源码 `6d5991d24bd8fd43fb5afec44e9ff842460e4b71` 在原采样旁增加数值明细，列出 Arena Chunk、Metaspace、Compiler 等 NMT 项及 Linux 页统计。旧附件的字节和重放方式保留。

2026-10-03 的 [60 秒四组](https://github.com/MoChiUaena/agent-triage/actions/runs/37123932091)和[十分钟四组](https://github.com/MoChiUaena/agent-triage/actions/runs/37124574163)全部通过，附件已独立重放。十分钟每组完成 4800 次请求、2400 次预设超时，4200 个响应关闭；原计数、窗口及资源归还门槛通过。

| Starter | 平台 | RSS 增长 MiB | 匿名页增长 MiB | 文件页 PSS 增长 MiB | Arena Chunk 增长 MiB | Metaspace 增长 MiB |
|---|---|---:|---:|---:|---:|---:|
| 关闭 | Linux | 30.60 | 30.48 | 0.06 | 15.64 | 0.37 |
| 开启 | Linux | 24.34 | 24.25 | 0.05 | 9.18 | 0.36 |
| 关闭 | Windows | 0.00 | 未采集 | 未采集 | 1.84 | 0.43 |
| 开启 | Windows | 0.00 | 未采集 | 未采集 | 13.61 | 0.36 |

这轮 Linux 的驻留增长主要出现在匿名页，文件页分摊量变化很小。Java 堆提交量在预热后保持 96 MiB；提交量固定并不意味着这些页始终驻留。匿名页同时包含 Java 堆、线程栈及原生分配，现有数字还不能把增长归到某个分配器。

Windows 关闭组另有约 21.19 MiB 的 Compiler 分项峰值；各分项峰值不一定同时出现，不能相加当作总增长。GC 后最终堆低于初始基线，关闭样本也纳入原增长检查。本次仍是同一 JVM 内的驱动、Servlet 与回环下游，独立 CI 主机的差值不直接代表 Starter 的生产成本。

原始附件见 [60 秒](samples/2026-10-03-memory-details/60s/)、[十分钟](samples/2026-10-03-memory-details/600s/)，包含 `memory.csv`、`memory-details.csv` 和 `summary.json`。逐文件校验见 [SHA256SUMS](samples/2026-10-03-memory-details/SHA256SUMS)。格式及重放检查见[资源检查](../RUNTIME_RESOURCES.md)。

最新主分支的一小时复跑在 Windows 出现请求超时，尚未确定原因，[失败记录](2026-10-03-http-window-audit.md)继续保留。十分钟的分项数据不替代最新版本的小时或数天稳定性验收。
