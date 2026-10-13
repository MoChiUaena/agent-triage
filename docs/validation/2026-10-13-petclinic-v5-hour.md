# Petclinic REST V5 一小时开关对照

[工作流](https://github.com/MoChiUaena/agent-triage/actions/runs/38379955575)使用源码 `7c91c66e808e607b8b470dfdae3d8cf0f08a1c9e` 和固定的 Petclinic REST 提交 `26a80b7a510af1fda0acdfb45d65aa480bdb7607`。同一台 Linux CI 主机先运行 Starter 开启组，再启动独立的关闭组；两组各持续 3,600 秒，每秒向真实回环服务发送两次请求。库存每次读超时，支付交替返回 200 和 500。全程使用固定规则，未调用外部模型。

| Starter | 请求 | 最大启动偏差 | 请求 p95 | 内存样本 | 预热后 RSS 峰值增长 | 后段与前段 RSS 中位数差 |
|---|---:|---:|---:|---:|---:|---:|
| 开启 | 7,200 | 17.225 ms | 105.427 ms | 242 | 10.48 MiB | +11.15 MiB |
| 关闭 | 7,200 | 3.721 ms | 105.095 ms | 242 | 11.85 MiB | +11.14 MiB |

逐请求收据核对了计划节奏、完成数和延迟。开启组的 V5 累计记录数为 7,202，包含负载前两次功能检查。由于观测窗口最多 15 分钟，结束时只核对末尾窗口：1,795 次请求、1,795 次库存超时，支付成功 897 次、5xx 898 次；Agent 冻结的窗口请求数也是 1,795。关闭组的观测接口不可用，但相同业务请求仍完成。进程清理检查通过。

同一 PID 的预热后、结束后内存分项也已重放：开启／关闭组的 RSS 分别增加 9.45／11.23 MiB，Java 堆提交量均未变化，线程数均未增加；NMT 非堆提交量分别减少约 3.13／2.07 MiB。这些数字与 RSS 不是可直接相加的口径。两组的后段 RSS 都高于前段；本次一小时结果不证明长期内存趋稳，也不能把两台依次启动的 JVM 的差值解释为 Starter 的精确开销。

工作流只上传请求时序、进程内存、NMT 数值及摘要。在仓库根目录下载附件，再用日志中独立记录的哈希重放：

```powershell
gh run download 38379955575 --name petclinic-v5-cost-3600 --dir target/petclinic-multi-cost-ci/38379955575
python scripts/petclinic_multi_cost_report.py target/petclinic-multi-cost-ci/38379955575 --source-commit 7c91c66e808e607b8b470dfdae3d8cf0f08a1c9e --seconds 3600 --expected-summary-sha256 45bd86ec541598784efdce0e9bfd326bd0ba638fb87b37140007a7dc2cbabc58
python scripts/petclinic_multi_cost_report.py target/petclinic-multi-cost-ci/38379955575 --source-commit 7c91c66e808e607b8b470dfdae3d8cf0f08a1c9e --seconds 3600 --expected-summary-sha256 39749cf2a2d499addcdd30f0373b30ab30cd1b53579ea1200f30b86d3ab108f0 --control
python scripts/petclinic_memory_report.py target/petclinic-multi-cost-ci/38379955575 --source-commit 7c91c66e808e607b8b470dfdae3d8cf0f08a1c9e --seconds 3600 --enabled-cost-sha256 45bd86ec541598784efdce0e9bfd326bd0ba638fb87b37140007a7dc2cbabc58 --control-cost-sha256 39749cf2a2d499addcdd30f0373b30ab30cd1b53579ea1200f30b86d3ab108f0 --enabled-profile-sha256 f9e34b74a7cbb095b201b3eace8975736c44d530ca0850961569d64ab1e955f9 --control-profile-sha256 66f4a79ddf4aabcb234ee5631c5f2b26c88b53bc743a01fa862d4f68ce462feb
```

本机下载后的三次独立哈希重放均通过。附件没有业务日志、请求内容、源码副本或密钥。数小时运行成本和先后顺序可能带来的偏差仍待核对；此前暂缓的 Windows 长时宿主问题继续单独处理。
