# 20 分钟 HTTP 资源复跑

[工作流 37719786080](https://github.com/MoChiUaena/agent-triage/actions/runs/37719786080) 使用固定源码 `d51257059c936fef4cc037fbb080883d9d323d9d`，开启请求阶段、安全点与跨进程心跳诊断。Windows/Linux 的 Starter 关闭、开启四组各运行 1200 秒，全部通过；四份附件按源码 SHA 和原始 CSV 独立重放通过。

每组完成 9600 次请求、8400 个响应打开与关闭。开启组记录 9600 次请求和 4800 次有类型超时；JDK 正文失败在这轮均未返回有类型超时。最大驱动落后为 202–261 毫秒，线程、队列、端口与关闭样本检查通过。Linux 两组的预热后 RSS 峰值增长为 39.24、56.56 MiB，主要落在匿名页；这些数字来自不同 CI 主机，不能直接归因于 Starter。

[12 份原始数值文件](samples/2026-10-08-host-scheduling-1200/)按下载字节保存，[SHA256SUMS](samples/2026-10-08-host-scheduling-1200/SHA256SUMS)逐文件校验。这轮证明同一源码完成了 20 分钟受控负载；[前一轮一小时 Windows 失败](2026-10-08-host-scheduling-hour.md)仍需用新的小时级运行核对。
