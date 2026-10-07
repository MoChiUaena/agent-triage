# HTTP 阶段诊断核对

源码 `8f2df4b2a66b3cab90ab68dc85694f05953e4418` 增加可选的请求阶段计时。默认关闭；手动检查可开启 `timing`，采样命令对应 `--http-timing`。读取预算、每秒八次请求、并发度、计数和资源回收门槛保持原值。

探针最多保存八个在途请求和 64 条最近阶段记录。首次异常失败在清理前输出读取正文、等待、写响应的设定延迟、墙钟耗时、线程 CPU 时间和驱动延迟。CPU 计时不可用时记为 `-1`；不记录请求内容、地址或异常消息。最后一个字节的写入计时不包含之后的响应关闭，关闭仍由原资源检查覆盖。

[60 秒四组](https://github.com/MoChiUaena/agent-triage/actions/runs/37607755527)在 Windows/Linux、Starter 关闭／开启状态全部通过。每组完成 480 次请求、240 次预设超时和 420 个响应关闭；开启组累计观测数完整。数值附件已按完整源码 SHA 独立重放，并核对 `httpTiming=true` 和内存明细哈希。

原始附件见 [60 秒目录](samples/2026-10-07-http-timing/60s/)，逐文件校验见 [SHA256SUMS](samples/2026-10-07-http-timing/SHA256SUMS)。附件含 `memory.csv`、`memory-details.csv` 和 `summary.json`；该模式的测量包含探针开销，复核拒绝混合启用与关闭状态。

最新主分支先前的[一小时 Windows 超时](2026-10-03-http-window-audit.md#最新主分支小时复跑)原因仍未确定。固定源码的[一小时阶段诊断](2026-10-07-http-hour-timing.md)复现了两条 Windows 失败，Linux 两组通过。短测通过、阶段墙钟与 CPU 时间差都不能证明长时停顿已经解决。

本地检查另保留一次失败：完整集成测试中，`RestTemplateIntegrationTest` 的正常请求期望 200，实际收到 504。单独复跑及之后完整集成复跑通过，期间没有修改客户端预算或测试代码。目前只确认它是间歇性现象，不能据此认定原因或修复；它也不能与 CI 的 Windows 长时失败直接视为同一问题。

运行和重放方式见[资源检查](../RUNTIME_RESOURCES.md#失败阶段时序)。
