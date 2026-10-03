# 持续运行资源检查

资源检查分别运行 Agent 取消流程和 Starter 请求上下文／JDBC 流程。它验证组件在反复使用后的回收行为，使用内存 H2、可控任务和 Spring 测试请求，不启动真实模型或读取本机业务数据。

## 运行

安装 JDK 21 并设置 `JAVA_HOME`，该目录需包含 `jcmd`。在仓库根目录运行：

```powershell
python scripts/resource_soak.py --component agent --seconds 3600
python scripts/resource_soak.py --component starter --seconds 3600
```

每条命令持续运行对应组件一小时，再等待关闭后的采样。先确认脚本能启动自己创建的 Maven 测试 JVM，并读取该进程的诊断信息。短检查可用 `--seconds 60`；不传时默认 3600。两个组件使用各自隔离的内存库和输出目录，不修改页面中的模型选择。

GitHub 的 `Runtime resource retention` 工作流可选 60、300 或 3600 秒；Windows/Linux 与 Agent/Starter 组成四个独立作业，单条失败不会取消其他路径。普通相关代码推送仍执行 300 秒检查。

## 覆盖

Agent 每轮交替阻塞工具或本地可控模型任务，取消四个运行中任务和一个排队任务，确认迟到结果不发布，再确认新任务可以完成。登记、队列与借用连接回到零；关闭后三个执行器终止。测试只删除自己创建的终态历史，业务历史的正常增长不计作执行器留存。

Starter 使用入站 V4，每三轮产生一次 503，其余为 204，确认响应分类及单纯服务器错误计数。每轮完成一个异步请求，记录正常 JDBC 和失败 SQL 两次观测，取消等待任务，再执行一次响应完成后的迟到查询并确认不增加观测计数。四个工作线程逐一检查上下文，借用连接归零，HTTP/JDBC 样本最多各 512 条；容量丢失后，一分钟窗口返回 422。关闭观测器后采样线程终止，应用拥有的池在测试结束时另行关闭。

这里没有测量完整 HTTP 服务的端到端吞吐、真实供应商取消或生产数据库驱动。线程和连接断言只对应上述受控流程。

## 采样与判定

脚本只读取测试方法公布的 JVM PID。JVM 使用 G1、`-Xms96m -Xmx256m` 和 NMT summary。每 30 秒读取堆使用量、Java 堆提交量、NMT 总预留量与提交量、NMT 线程数及进程驻留内存；新版采样还记录 Class、Thread、Code、GC、Other 和其余 NMT 提交量。Windows 另记录私有提交量。预留地址空间与实际驻留内存分列，空字段表示该平台未采集，不补零。

小时检查排除前 120 秒预热，以第一个预热后样本为基线；关闭后的样本也纳入增长门槛。NMT 总提交量扣除 Java 堆提交量后，上浮门槛为 64 MiB；进程驻留量上浮门槛为 128 MiB。原 Java 测试继续检查 GC 后堆峰值和最终留存相对初始基线上浮不超过 64 MiB。这些数值是本次受控验收的门槛，不能单独证明没有泄漏。

相邻稳态样本、稳态起点与关闭边界允许采样间隔外的 5 秒调度余量；小时检查超过 35 秒的采样空缺会失败。缺少 NMT、PID 不符、采样不足、持续时间不足、关闭后未采样、Maven 失败或资源回收计数不符也会失败。脚本为诊断命令和整个运行设置截止时间，失败时只清理自己启动的测试 JVM 和 Maven 进程。

输出在忽略的 `target/resource-soak/` 下。CI 保留数值 `memory.csv` 与 `summary.json`；开启内存明细时另保留 `memory-details.csv`，HTTP 工作流默认开启。摘要包含源码提交、平台、运行计数和关闭结果；不上传 Maven 日志、状态文件、数据库或模型设置。数值附件保留 14 天，公开验收记录另保存结论和可复核的摘要。

## 复核附件

下载同一次工作流的四个数值附件，解压到一个空目录，例如 `target/resource-artifacts/`。从工作流详情取得完整的源码 SHA，再运行：

```powershell
python -m unittest discover -s scripts -p 'test_*resource*.py'
$resourceSourceCommit = '从工作流复制的完整源码SHA'
python scripts/resource_report.py target/resource-artifacts --source-commit $resourceSourceCommit --seconds 3600
```

复核命令要求 Agent / Starter 与 Windows / Linux 四个组合各出现一次，并重算原始 CSV 的稳态增长、峰值和关闭样本。它也检查 NMT 总量与堆／非堆量一致、采样覆盖稳态首尾、运行计数完整、资源归还和源码工作区干净。传入的时长应与本次工作流输入一致；验证失败时不生成通过表。可用 `--output target/resource-table.md`保存表格。

旧采样器的汇总未包含关闭阶段增长。复核工具先核对其原始定义，再将关闭样本纳入当前门槛，输出重算后的数值；不会把旧汇总直接当作新版结果。

汇总一致性检查不能代替附件来源核对。请确认附件属于所记录的工作流和源码提交；这些受控数值也不能用于推断生产吞吐或数天运行的稳定性。

## 真实 HTTP 对照

HTTP 检查使用随机回环端口的 Servlet 应用和下游服务，分别关闭、开启 Starter。两组都保留测试用的响应关闭计数和上下文审计，每秒发送 8 次请求，驱动并发度为 4。每秒包含三个正常 GET/POST/PUT、三个 Simple 工厂响应头超时、一个 Simple 正文超时和一个 JDK 无可靠超时类型的正文读取失败；下游核对 POST/PUT 正文，应用保持原请求方法。正常返回固定延迟 350 毫秒，读取预算为 2 秒；故障请求的读取预算为 250 毫秒，下游等待 1 秒。两个预算用于区分正常调度延迟与故障场景，固定请求速率保持一致。正常与故障请求混排，Simple 故障请求和所有故障响应使用 Connection: close；正常请求要求实际复用连接。这条策略不用于推断纯正常长连接流量的开销。

```powershell
python scripts/resource_soak.py --component http-disabled --seconds 600
python scripts/resource_soak.py --component http-enabled --seconds 600
```

脚本仍由 Maven/JUnit 启动测试 JVM。它检查全部请求的累计计数、每 30 秒及最后一分钟的完整观测窗口、异常类型、响应关闭、工作线程和 Servlet 上下文。JDK 无可靠超时类型的正文失败单独计数，不按异常消息判断超时。关闭 Servlet、两个 JDK HTTP 客户端、驱动线程和下游后，核对执行器终止、队列清空和原端口关闭，再采集关闭样本。

窗口核对在已发请求全部完成后计算查询时间，让两端与完成记录至少相隔 50 毫秒。查询时间最多向前移动一秒，不通过睡眠等待边界；没有合适边界时检查失败。

驱动保持四个工作线程和八个排队位置。队列瞬时满时，提交最多等待两秒；超时、停止或中断时仍拒绝提交。每个请求实际开始时间继续与原计划时刻核对，等待也计入两秒发送延迟门槛；未完整完成全部请求的运行不能通过。

GitHub 的 `HTTP resource comparison` 工作流包含两种开关状态与 Windows/Linux 四组，支持 60、600、1200 和 3600 秒；相关推送默认执行 60 秒。下载同次工作流的四组数值附件后，使用独立的 HTTP 矩阵复核：

```powershell
python scripts/resource_report.py target/http-resource-artifacts --workload http --source-commit $resourceSourceCommit --seconds 600
```

HTTP 收据的 connectionPolicy=2 表示故障连接关闭，并验证正常连接复用。旧收据缺少该字段时按原共享池策略 1 重放；四组不得混合策略。

### 内存分项

采样命令增加 `--memory-details` 时，另保存数值 `memory-details.csv`，不改变原 `memory.csv`。HTTP 工作流默认开启这项采集。明细包含 Arena Chunk、Metaspace、Compiler、Internal、Symbol 等 NMT 项，以及 Linux 的匿名页、私有／共享页和 PSS。Windows 的 Linux 字段留空；NMT 未报告的项目也留空，不当作测得的零值。Metaspace 和 Symbol 缺失时采集失败。

摘要中的 `memoryDetails` 标明格式版本、行数与 SHA-256。重放要求每行的 PID、阶段和时间与原采样一一对应，并检查 NMT 剩余量和 Linux 页统计的一致性；文件缺失、被修改或矩阵混用新旧采集配置时拒绝通过。原附件没有这项标记时继续按旧格式复核，原始字节无需转换。

NMT 项按 KiB 四舍五入，细分剩余量可包含很小的负舍入差；超过 21 KiB 的负差会失败。Linux `smaps_rollup` 的 RSS 应等于私有和共享的干净／脏页之和。旧内核未提供 PSS 分类时，三项分类都保持未报告。

重放表增加分项增长，包含关闭样本；任一所需点未报告的项目不计算增长。NMT 提交量、RSS 驻留量和 PSS 分摊量描述不同的内存属性，不能直接相减当作分配来源。读数来自同一次采样过程中的顺序查询，并非原子快照。明细只保留固定指标名和数值，不包含映射地址、文件路径、堆内容或环境变量。

HTTP 复核要求新增 NMT 分项、固定速率下的完整请求数、实际观测计数和关闭结果；Class、Thread、Code、GC 缺失时采集失败，NMT 未报告的 Other 按零处理。30 秒内的检查要求最终窗口含全部请求，较长检查为自然过期保留有限余量，并要求超时数与固定故障比例相符，不接受原组件循环的附件替代。输出包含资源增长和 NMT 分项；各分项峰值可能发生在不同时间，不能相加当作同一时刻的总峰值。

这里测量的是同一 JVM 中的驱动、Servlet、观测器和回环下游。两组运行在不同 CI 虚拟机，GC 时点也不同，数值差不能直接当作 Starter 的生产开销。这是固定故障比例的资源验收，尚未覆盖真实业务吞吐、远程网络或数天运行。
