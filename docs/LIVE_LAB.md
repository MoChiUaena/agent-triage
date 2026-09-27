# 本地真实请求链路

`sample-service`（订单）和 `inventory-service`（库存）是两个独立 Spring Boot 进程。订单接口通过 HTTP 调用库存服务；正常场景库存响应约 15ms，故障场景等待 600ms，超过订单客户端 300ms 请求总时限。订单接口返回 504 时，错误被写入 `data/sample-errors.jsonl`，请求耗时与 traceId 也进入有界窗口记录。

订单与库存服务使用 Micrometer 记录请求耗时，订单服务还记录请求总数；Actuator 的 `/actuator/metrics` 可直接核对累计值。排障助手设置 `TRIAGE_OBSERVATION_SOURCE=LIVE` 后，通过只读 HTTP 接口读取订单服务按时间窗口计算的 p95、Micrometer 累计请求数和 JSON 日志中的错误事件。排障任务的 `synthetic=false`；排障文档仍是仓库内提供的规则，不是运行时观测。DEMO 模式使用固定规则分析实际观测，MODEL 模式仍需单独配置模型服务。

页面上的“生成正常请求”和“触发库存超时”是显式的实验控制，先清空样例观测，再向订单接口发 5 次请求。它与 Agent 的只读工具分开。没有请求时，结果应为证据不足；正常请求后可以查看窗口指标；超时请求后应看到超时率、错误事件和 traceId。

本地快速验证见 [README](../README.md#跑通本地真实请求)。自动复查脚本为 `scripts/live_smoke.py`，结果写入 `target/live-smoke/`。窗口请求记录最多 10000 条、查询窗口最多 60 分钟；JSON 错误日志达到 2MB 后清空旧内容继续写入。Micrometer 指标是进程启动以来的累计值，与窗口数值分开标注。这仍是本地故障实验，不是生产监控接入，也没有真实用户流量。

[本次本地复查结果](validation/2026-09-27-live-smoke.json)包含空窗口、正常和超时三个结果，以及页面流量控制的检查。实际 p95 会随机器波动。
