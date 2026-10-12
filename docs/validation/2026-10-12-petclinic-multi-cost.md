# Petclinic REST 多下游短时运行成本

固定源码 `bf7d76fa36cd5358ac5e17287655b3b30872cd36`，公开项目提交 `26a80b7a510af1fda0acdfb45d65aa480bdb7607`。在 GitHub Linux 虚拟机启动 Petclinic REST、Agent 和两个回环下游，按计划每秒发送 2 个入站请求：库存调用在 100 ms 读取时限下超时，支付响应交替为 200／500。进程级 RSS 和 `smaps_rollup` 私有页每 15 秒采样一次；预热分别排除前 12／60 秒。未启用 JVM 附加诊断，也未调用模型。

| 运行 | 负载请求 | 最大启动偏差 | 请求 p95 | 内存样本 | 预热后 RSS 峰值增长 | 私有页峰值增长 |
|---|---:|---:|---:|---:|---:|---:|
| [60 秒](https://github.com/MoChiUaena/agent-triage/actions/runs/38215888264) | 120 | 4.754 ms | 107.913 ms | 6 | 11.18 MiB | 11.18 MiB |
| [300 秒](https://github.com/MoChiUaena/agent-triage/actions/runs/38216283987) | 600 | 7.478 ms | 107.876 ms | 22 | 18.56 MiB | 18.56 MiB |

两次运行都达到精确的计划请求数，最大启动偏差低于 250 ms；请求时间和内存采样没有超过允许的覆盖间隔。60／300 秒窗口连同最初的两个验收请求分别记录 122／602 个入站请求和相同数量的库存超时；支付 2xx 与 5xx 分别为 61／61 和 301／301。Agent 完成第二次窗口排查并保存相同分项，工作流核对了验收进程退出。

只上传数字 `requests.csv`、`cost.csv` 和 `cost-summary.json`，未上传应用日志、观测原文、源码副本或密钥。下载对应工作流的 `petclinic-v5-cost-60` 或 `petclinic-v5-cost-300` 附件后可重放：

```powershell
python scripts/petclinic_multi_cost_report.py target/petclinic-multi-cost-ci/38215888264 --source-commit bf7d76fa36cd5358ac5e17287655b3b30872cd36 --seconds 60 --expected-summary-sha256 0e100ad966e194408b3da6f3b9a39b80badf8831693a184ec725024542c326de --legacy-requests-sha256 ea75808011d89fb6b35001727c0b1ebe0ba49ad6cf23fb23a8371d7cb159f2f4 --legacy-memory-sha256 fcbf97fa85fb8a95a04e7841b7c7cffb04510b7089d61b134854e01bf2219710
python scripts/petclinic_multi_cost_report.py target/petclinic-multi-cost-ci/38216283987 --source-commit bf7d76fa36cd5358ac5e17287655b3b30872cd36 --seconds 300 --expected-summary-sha256 050cf0dc1f00f5310ac0c54ed25ee275aa733b479f784b50f8fbc129d65fa2fc --legacy-requests-sha256 01c792f13d173a45c3c927d5249a122a691ea9ac0b80d3aa9589de47e2d3d145 --legacy-memory-sha256 c9644ef8f91eb2e041d31ae6b88ce5f46947fb60b61d9f9d135f2dd5ac27018d
```

重放核对来源提交、固定上游、逐请求顺序与计划时间、延迟摘要、采样覆盖和内存增长。两份摘要的 SHA-256 分别为 `0e100ad966e194408b3da6f3b9a39b80badf8831693a184ec725024542c326de` 和 `050cf0dc1f00f5310ac0c54ed25ee275aa733b479f784b50f8fbc129d65fa2fc`。

这两份附件产生于摘要内绑定 CSV 哈希之前，因此重放命令显式使用上述已知哈希。新版摘要会内置两份 CSV 的 SHA-256；下载附件后还须从独立的工作流日志或经审阅的记录取得摘要 SHA-256 并传给重放命令。仅有附件内部哈希只能证明文件相互一致，不能防止整组文件被一起改写。工作流现场使用显式 `--trust-current-run`，打印摘要哈希供之后核对；重放同时拒绝非有限时间、提前发送和错误预热时长。

哈希绑定改动在[新版 60 秒运行](https://github.com/MoChiUaena/agent-triage/actions/runs/38219342438)中通过了“采集、现场重放、数字附件上传、进程清理”四步。来源提交 `64b5b041ba69f951f2024216135e80c1c9022cf2`，120 个请求的 p95 为 107.671 ms，最大启动偏差 4.878 ms，预热后 RSS／私有页峰值增长均为 3.17 MiB。工作流日志给出的摘要 SHA-256 为 `7beef0ebfd575ff209ff4432f7e8a26525fe31288b25433f263e059ced420baf`；下载附件后按独立哈希重放：

```powershell
python scripts/petclinic_multi_cost_report.py target/petclinic-multi-cost-ci/38219342438 --source-commit 64b5b041ba69f951f2024216135e80c1c9022cf2 --seconds 60 --expected-summary-sha256 7beef0ebfd575ff209ff4432f7e8a26525fe31288b25433f263e059ced420baf
```

这次与上表运行处于不同虚拟机，不能将 3.17 MiB 与 11.18 MiB 直接比较为同一进程的内存变化。

上表仅是 Linux 短时成本基线。300 秒运行的 RSS 与私有页在末尾仍接近窗口峰值，无法据此判断长期是否趋稳或泄漏；两次运行处于不同虚拟机、预热基点也不同，增长值不能直接相减。上表没有同机关闭 Starter 的对照，所以这些数值也不能归因于 Starter。Windows 本地试跑曾出现一次支付读取超时；该失败未计入通过结果，未放宽 500 ms 支付读取时限或之前的 Windows 小时级门槛。

## 同机开关对照

[60 秒工作流](https://github.com/MoChiUaena/agent-triage/actions/runs/38224275207)和[300 秒工作流](https://github.com/MoChiUaena/agent-triage/actions/runs/38225101023)各自在一台 Linux CI 主机上依次启动两个独立的 Petclinic JVM：先开启 Starter，再关闭 Starter。两组都对相同回环下游发送每秒 2 个请求，库存读取超时、支付交替返回 200／500。关闭组的业务结果与开启组相同，观测接口消失；开启组的 Agent 证据和历史仍通过核对。Agent 进程在每次开关对照期间保持运行。60／300 秒的源码分别为 `f09a94220693e0b2df66cabb4ec5bb44c4f76466` 和 `65fe243c7091b2be14b6df966fe3e2017d2671fb`。

| 时长 | Starter | 负载请求 | 最大启动偏差 | 请求 p95 | 内存样本 | 预热后 RSS 峰值增长 | 私有页峰值增长 |
|---|---|---:|---:|---:|---:|---:|---:|
| 60 秒 | 开启 | 120 | 7.969 ms | 109.638 ms | 6 | 3.22 MiB | 3.22 MiB |
| 60 秒 | 关闭 | 120 | 7.914 ms | 108.670 ms | 6 | 2.71 MiB | 2.71 MiB |
| 300 秒 | 开启 | 600 | 7.426 ms | 107.389 ms | 22 | 13.14 MiB | 13.14 MiB |
| 300 秒 | 关闭 | 600 | 5.368 ms | 108.020 ms | 22 | 7.98 MiB | 7.98 MiB |

工作流仅上传各组的请求时间、进程内存和摘要数字文件。下载两次工作流的 `petclinic-v5-cost-60`、`petclinic-v5-cost-300` 附件后，使用日志中独立记录的摘要哈希分别重放：

```powershell
python scripts/petclinic_multi_cost_report.py target/petclinic-multi-cost-ci/38224275207 --source-commit f09a94220693e0b2df66cabb4ec5bb44c4f76466 --seconds 60 --expected-summary-sha256 b00ecc879ce01737fa513432fd91308071a9e3d35e7627349cd7b9d9eba68d60
python scripts/petclinic_multi_cost_report.py target/petclinic-multi-cost-ci/38224275207 --source-commit f09a94220693e0b2df66cabb4ec5bb44c4f76466 --seconds 60 --expected-summary-sha256 032b7f6fe859a26976d6664abcefcffa30da523d4d78abe564468921311df672 --control
python scripts/petclinic_multi_cost_report.py target/petclinic-multi-cost-ci/38225101023 --source-commit 65fe243c7091b2be14b6df966fe3e2017d2671fb --seconds 300 --expected-summary-sha256 d73513373e5c5b4a66a0155c5ab7570a669ad5f88f6de21540af2fcc685227a8
python scripts/petclinic_multi_cost_report.py target/petclinic-multi-cost-ci/38225101023 --source-commit 65fe243c7091b2be14b6df966fe3e2017d2671fb --seconds 300 --expected-summary-sha256 8659da8b540cb858f345f8a528b57a6548ea14e517b982af6b1f6d81c4c71229 --control
```

下载附件的四组重放均通过。每次开关对照在同一台 CI 主机上完成，但运行顺序固定、JVM 分别启动，启动状态和机器背景负载仍可能影响差值。60 秒两组的增长差为 0.51 MiB，300 秒两组为 5.16 MiB；它们仅供排查参考，不作为 Starter 的精确开销。300 秒两组的最后一个内存样本仍在各自峰值附近，不能据此判断长期内存是否趋稳或泄漏。
