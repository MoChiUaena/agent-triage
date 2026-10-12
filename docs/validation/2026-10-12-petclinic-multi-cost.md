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

本次仅是 Linux 短时成本基线。300 秒运行的 RSS 与私有页在末尾仍接近窗口峰值，无法据此判断长期是否趋稳或泄漏；两次运行处于不同虚拟机、预热基点也不同，增长值不能直接相减。没有同机关闭 Starter 的对照，所以这些数值也不能归因于 Starter。Windows 本地试跑曾出现一次支付读取超时；该失败未计入通过结果，未放宽 500 ms 支付读取时限或之前的 Windows 小时级门槛。
