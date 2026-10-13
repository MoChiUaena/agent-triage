# Petclinic REST 内存分项短测

[60 秒工作流](https://github.com/MoChiUaena/agent-triage/actions/runs/38354065272)使用源码 `c7e6f045c6046d73081e8e330b56f8106b5d91bb` 和固定的公开 Petclinic REST 提交。在同一台 Linux CI 主机上先运行 Starter 开启组，再运行关闭组；两组各完成 120 个真实回环请求，通过原有 250 ms 启动偏差、观测计数、Agent 证据和进程清理检查。

诊断模式为两个 Petclinic JVM 开启 JDK 21 的 NMT summary。每组在负载预热后的第一个成本样本附近，以及负载结束后，依次读取同一 PID 的 Linux 页统计、`VM.native_memory` 和 `GC.heap_info`。首尾采样分别约在第 15 秒和第 62 秒；单次采样最长 453 ms。读取不是原子操作，因此记录采样前后 RSS，并在重放时检查 PID、时间距离、采样跨度、NMT 分项与成本收据的哈希。

| Starter | RSS／匿名页变化 | 堆已用变化 | 堆提交变化 | NMT 非堆提交变化 | 其中 Code 提交变化 | 线程数变化 |
|---|---:|---:|---:|---:|---:|---:|
| 开启 | +3.23 MiB | −41.52 MiB | 0 | +2.22 MiB | +2.48 MiB | 0 |
| 关闭 | +2.50 MiB | +24.51 MiB | 0 | +0.78 MiB | +2.16 MiB | 0 |

下载工作流的 `petclinic-v5-cost-60` 附件后，用日志中独立记录的四个摘要哈希重放成本与分项收据：

```powershell
python scripts/petclinic_memory_report.py target/petclinic-multi-cost-ci/38354065272 --source-commit c7e6f045c6046d73081e8e330b56f8106b5d91bb --seconds 60 --enabled-cost-sha256 f16fb17e125ee882a02889a2e09f723f3aee148ae364346adacbd80379e66dd8 --control-cost-sha256 4d1786e45b1509b26a365063bc4bf47773575809e00deaed81f6abd59f58597c --enabled-profile-sha256 df5bc9f933a76e2e58a189ccdfe59662a78b400f0224add787460485598263d9 --control-profile-sha256 b732c75599ae5e3ef492679d086d0b33d48abf77ceba66a26e65c89e06925a5a
```

下载附件后独立重放通过。附件只包含请求时间、进程内存、分项数值及各自摘要；原始 JVM 输出、应用日志、请求内容和密钥均未上传。

两组的 Code 提交量都增加了，堆已用却分别下降和上升；NMT 提交量与进程驻留页也不是同一种计量，不能将表中分项相加归因 RSS 增长。两台 JVM 固定先后运行，60 秒只能检查采样链路，不能判断长期内存是否趋稳。更早的[首轮诊断](https://github.com/MoChiUaena/agent-triage/actions/runs/38353116142)在预热前取基点，其收据保留可重放，但不用于本表的预热后比较。
