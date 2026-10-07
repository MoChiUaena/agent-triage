# HTTP 安全点阶段诊断

先前的[一小时失败](2026-10-07-http-hour-timing.md)中，Windows 两组都出现正常请求超时，多个回环下游线程同时在预设延迟阶段等待超过两秒，驱动也发生调度延迟；当次 GC 计时没有增加。现有数据无法判断是否发生了非 GC 的 JVM 安全点停顿。

源码 `0fcd45c8e5da31f2e4a1d09a97a7cfe713c32678` 只在 HTTP 时序诊断开关开启时，为自有测试 JVM 写入 JDK 21 安全点日志。Maven 测试结束后，采样器核对日志包含数值事件；失败时只输出全程最长安全点、失败时 JVM 运行时间附近的次数及最多八条纯数字耗时。原始 JVM 日志留在忽略提交的测试目录，不进入 CI 附件。请求速率、超时预算、工作线程、资源门槛和默认运行方式保持不变。

[60 秒四组](https://github.com/MoChiUaena/agent-triage/actions/runs/37628321381)在 Windows/Linux、Starter 关闭／开启状态全部通过。每组完成 480 次请求和 420 个响应关闭；四组数值附件按完整源码 SHA、原始内存明细哈希和关闭样本重放通过。原始附件见 [60 秒目录](samples/2026-10-07-safepoint-timing/60s/)，逐文件哈希见 [SHA256SUMS](samples/2026-10-07-safepoint-timing/SHA256SUMS)。

两次更早的诊断短测（[37626194392](https://github.com/MoChiUaena/agent-triage/actions/runs/37626194392)、[37627399289](https://github.com/MoChiUaena/agent-triage/actions/runs/37627399289)）工作负载结束后被安全点解析校验拒绝，不能算通过。修正后的短测只确认诊断链路可用；[固定源码的一小时复跑](2026-10-07-safepoint-hour.md)中 Linux 两组通过、Windows 两组失败，长时停顿原因仍未确定。
