# 原生堆整理数值附件

[60 秒运行 37618576508](https://github.com/MoChiUaena/agent-triage/actions/runs/37618576508)与[600 秒运行 37619126906](https://github.com/MoChiUaena/agent-triage/actions/runs/37619126906)均使用源码 `2898b55d9f06a5edd7c60eaf961d3061de0be084`，各含 Linux 关闭／开启两组。16 个 `.csv` 和 `.json` 文件按 GitHub 附件原始字节保存，路径相对于本目录的 [SHA256SUMS](SHA256SUMS) 可逐项校验。

两轮均通过 `native_trim_report.py` 按运行编号、完整源码 SHA、原关闭样本和数值哈希重放。整理后的行是独立实验读数，不属于原资源增长门槛；结论与限制见[核对记录](../../2026-10-07-native-heap-trim.md)。
