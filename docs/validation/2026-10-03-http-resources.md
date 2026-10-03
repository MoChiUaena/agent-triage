# 真实 HTTP 服务资源验收

在随机回环端口的 Servlet 应用和下游服务中，以同一组请求对比 Starter 关闭、开启后的资源增长。负载驱动、应用和下游都在同一个 Maven/JUnit 测试 JVM 中。

- 源码：`a9b87ca8c146a098e15e86cfce7122be46470349`。
- 60 秒验收：[37091214184](https://github.com/MoChiUaena/agent-triage/actions/runs/37091214184)，Windows/Linux 四组通过，附件已独立重放。
- 每秒 8 次请求，驱动并发度 4；三个正常 GET/POST/PUT、三个 Simple 响应头超时、一个 Simple 正文超时和一个 JDK 正文读取失败。
- 正常返回延迟 350 毫秒，读取预算 2 秒；故障请求读取预算 250 毫秒，下游等待 1 秒。JDK 正文失败没有可靠的超时异常类型，单独计数。

## 60 秒结果

每组完成 480 请求、180 正常返回和 300 个预设失败；240 次有类型超时和 60 次无类型正文失败。420 个已取得的响应全部关闭，POST/PUT 正文一致，上下文审计无残留。关闭后两 HTTP 客户端终止、执行器和队列归还、Servlet 与下游原端口不再接收连接。

| Starter | 平台 | 观测请求 / 超时 | 最后一分钟请求 / 超时 | GC 后堆变化 MiB | NMT 非堆增长 MiB | RSS 增长 MiB |
|---|---|---:|---:|---:|---:|---:|
| 关闭 | linux | 0 / 0 | 0 / 0 | -0.31 | 13.51 | 4.58 |
| 关闭 | windows | 0 / 0 | 0 / 0 | -1.10 | 16.60 | 17.40 |
| 开启 | linux | 480 / 240 | 474 / 237 | 0.41 | 5.62 | 9.45 |
| 开启 | windows | 480 / 240 | 474 / 237 | 0.48 | 5.85 | 7.34 |

关闭组不安装观测器，表中零表示没有生成观测记录。尾部窗口按已完成请求核对，最早的请求可自然过期；累计请求和超时仍要求完整。各组都有运行与关闭样本，NMT 非堆 64 MiB、RSS 128 MiB、GC 后堆 64 MiB 门槛未超限。

## 首轮十分钟失败

[37090078761](https://github.com/MoChiUaena/agent-triage/actions/runs/37090078761) 的 Windows 两组通过，Linux 两组失败，不能作为整体验收通过记录。开启组约第 243 秒的正常请求收到 504；关闭组最终有类型超时为 2399，预期 2400。首轮源码为 `3633cdfe0aafe5ac5907378df0cf1b4dc7982920`。

原夹具所有请求共用 250 毫秒读取预算。350 毫秒正常延迟回归确认会被误判为失败，因此正常预算改为 2 秒，故障预算保持 250 毫秒，并加上实际发送延迟、GC 时间和异常类型诊断。首轮底层延迟及那一次类型差异未确定，后续需重新运行，不能用调整预算证明产品问题已修复。

## 测量边界

两组使用独立 CI 虚拟机，GC 时点不同，资源差值不能直接当作 Starter 的生产开销。NMT 分项已保存，但 RSS 增长的分配来源仍需核对；真实业务吞吐、远程网络和数天稳定性不在这条固定故障比例负载中。运行与重放方式见 [资源检查](../RUNTIME_RESOURCES.md)。

## 原始数值

CSV 保留附件原始字节，summary.json 包含源码、平台、计数和关闭结果；未加入 Maven 日志、业务数据或模型设置。

| 文件 | SHA-256 |
|---|---|
| [samples/2026-10-03-http-resources/60s/http-disabled-linux/memory.csv](samples/2026-10-03-http-resources/60s/http-disabled-linux/memory.csv) | 7700ec2bf8dac2a6520432b2aeba7cd23110a84107022cea781d3ed9deb298d6 |
| [samples/2026-10-03-http-resources/60s/http-disabled-linux/summary.json](samples/2026-10-03-http-resources/60s/http-disabled-linux/summary.json) | c791ac658d13e0b3e6cc9f1e689dbacec28552c7793ef3e4f803c9ac0f6ba181 |
| [samples/2026-10-03-http-resources/60s/http-disabled-windows/memory.csv](samples/2026-10-03-http-resources/60s/http-disabled-windows/memory.csv) | 6c9623ca9d0c76d3cbd38994da1453b6a0a3f5d5968cf4c17f7d1562f66e555d |
| [samples/2026-10-03-http-resources/60s/http-disabled-windows/summary.json](samples/2026-10-03-http-resources/60s/http-disabled-windows/summary.json) | 071cd60025db70b04f8db4d7574bdaeefbc692b563f17afa4464252d5727c4a6 |
| [samples/2026-10-03-http-resources/60s/http-enabled-linux/memory.csv](samples/2026-10-03-http-resources/60s/http-enabled-linux/memory.csv) | 2ad5baaf8fc7da013c12fdd6854b43f37a7c613a024ce65ccebaaae12ccd40db |
| [samples/2026-10-03-http-resources/60s/http-enabled-linux/summary.json](samples/2026-10-03-http-resources/60s/http-enabled-linux/summary.json) | 26b32114f13d5315d4d46d1243e7d244d4840891351f4de511549f601697783a |
| [samples/2026-10-03-http-resources/60s/http-enabled-windows/memory.csv](samples/2026-10-03-http-resources/60s/http-enabled-windows/memory.csv) | 2b6eef690208d672e185382722713150d434ba883684b4dc7025169776d57ed5 |
| [samples/2026-10-03-http-resources/60s/http-enabled-windows/summary.json](samples/2026-10-03-http-resources/60s/http-enabled-windows/summary.json) | 0ca48fe339190dbd3992cfc874673ed1a50358e67407e30e5f9e9ba4b86e0b02 |
