# HTTP 长时运行复验

源码 `bb3ace8a57a5be920612e69679f310728f64741a` 修正了两处验收夹具问题：下游写入失败交还 HttpServer 清理，窗口核对直接计算时间边界，避免轮询暂停发送。流量、超时预算、请求计数和资源门槛保持不变；故障连接关闭，正常连接仍验证复用。

[60 秒](https://github.com/MoChiUaena/agent-triage/actions/runs/37110851693)和[十分钟](https://github.com/MoChiUaena/agent-triage/actions/runs/37111025147)的 Windows/Linux 四组全部通过，数值附件已独立重放。十分钟每组完成 4800 次请求、2400 次预设超时、600 次无类型正文失败，4200 个响应全部关闭；上下文、执行器、队列和原监听端口的回收检查通过。

| Starter | 平台 | GC 后堆变化 MiB | NMT 非堆增长 MiB | RSS 增长 MiB |
|---|---|---:|---:|---:|
| 关闭 | Linux | -2.98 | 23.26 | 45.28 |
| 关闭 | Windows | -2.97 | 11.01 | 0.00 |
| 开启 | Linux | -0.98 | 4.44 | 32.74 |
| 开启 | Windows | -1.01 | 13.29 | 5.15 |

关闭后堆使用量低于初始基线，旧夹具的留存增长没有在这轮重现。两组运行在独立 CI 虚拟机，NMT 与 RSS 的差值不能直接归因于 Starter；Linux RSS 增长的分配来源仍未确定。

![十分钟资源曲线](samples/2026-10-03-http-resources/policy-2/bounded-audit/600s/memory.png)

原始数值保留在 [60 秒](samples/2026-10-03-http-resources/policy-2/bounded-audit/60s/)和[十分钟](samples/2026-10-03-http-resources/policy-2/bounded-audit/600s/)目录，逐文件校验见 [SHA256SUMS](samples/2026-10-03-http-resources/policy-2/bounded-audit/SHA256SUMS)。[前两次小时失败](2026-10-03-http-resources.md)继续保留，不能用本轮十分钟结果替代小时验收。运行和重放命令见[资源检查](../RUNTIME_RESOURCES.md)。
