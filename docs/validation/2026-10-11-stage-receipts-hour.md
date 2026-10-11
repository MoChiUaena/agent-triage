# HTTP 阶段收据一小时复跑

[工作流 38114021458](https://github.com/MoChiUaena/agent-triage/actions/runs/38114021458) 使用固定提交 `424a64732d9a7a8df174cfd9e27a4abd70496810`，开启阶段、安全点和 JVM／主机心跳诊断。Linux、Windows 的 Starter 开启与关闭四组都完成 3,600 秒，各处理 28,800 次请求并通过原资源门槛。测试还核对每次请求的正文读取和延迟阶段入口；四组均未触发阶段缺口。

下载四组[数值附件](samples/2026-10-11-stage-receipts-hour/)后，以原提交和 3,600 秒调用 `resource_report.py --workload http`，完整矩阵独立重放通过。文件哈希见 [SHA256SUMS](samples/2026-10-11-stage-receipts-hour/SHA256SUMS)。

| 模式 | 系统 | 请求数 | 观测请求／超时 | NMT 非堆峰值增长 | RSS 峰值增长 |
|---|---|---:|---:|---:|---:|
| 关闭 | Linux | 28,800 | 0／0 | 17.61 MiB | 84.42 MiB |
| 关闭 | Windows | 28,800 | 0／0 | 15.80 MiB | 0.67 MiB |
| 开启 | Linux | 28,800 | 28,800／14,400 | 16.30 MiB | 54.47 MiB |
| 开启 | Windows | 28,800 | 28,800／14,400 | 16.26 MiB | 0.00 MiB |

增长从预热后的首个样本计算，包含关闭样本；四组来自不同 CI 主机，不能用表中差值计算 Starter 的生产开销。[上一轮](2026-10-11-request-receipts-hour.md)的 Windows 两组失败仍然成立。本轮通过说明新阶段收据没有在这次运行中发现缺口，尚不足以证明 Windows 长时问题已消失。
