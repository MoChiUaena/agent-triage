# 一小时安全点诊断数值

来源：[工作流 37628886514](https://github.com/MoChiUaena/agent-triage/actions/runs/37628886514)，源码 `0fcd45c8e5da31f2e4a1d09a97a7cfe713c32678`，时序诊断开启，时长 3600 秒。文件均按附件原始字节保存；路径相对于本目录的 [SHA256SUMS](SHA256SUMS) 可逐项校验。

`linux-passed` 含两组完整关闭样本和摘要，分别通过严格重放。`windows-failed` 只有失败前的数字 CSV，没有关闭样本；失败状态、请求阶段和安全点数字见工作流日志。这些文件不能当作 Windows 通过收据。结果解释见[复跑记录](../../2026-10-07-safepoint-hour.md)。
