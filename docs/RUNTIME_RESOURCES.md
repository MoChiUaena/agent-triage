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

Starter 使用入站 V4，交替产生 204 与 503，确认响应分类及单纯服务器错误计数。每轮完成一个异步请求，执行正常 JDBC 和失败 SQL，取消等待任务，再检查响应完成后的迟到查询。四个工作线程逐一检查上下文，借用连接归零，HTTP/JDBC 样本最多各 512 条；容量丢失后，一分钟窗口返回 422。关闭观测器后采样线程终止，应用拥有的池在测试结束时另行关闭。

这里没有测量完整 HTTP 服务的端到端吞吐、真实供应商取消或生产数据库驱动。线程和连接断言只对应上述受控流程。

## 采样与判定

脚本只读取测试方法公布的 JVM PID。JVM 使用 G1、`-Xms96m -Xmx256m` 和 NMT summary。每 30 秒读取堆使用量、Java 堆提交量、NMT 总预留量与提交量、NMT 线程数及进程驻留内存；Windows 另记录私有提交量。预留地址空间与实际驻留内存分列，空字段表示该平台未采集，不补零。

小时检查排除前 120 秒预热，以第一个预热后样本为基线。NMT 总提交量扣除 Java 堆提交量后，上浮门槛为 64 MiB；进程驻留量上浮门槛为 128 MiB。原 Java 测试继续检查 GC 后堆留存相对初始基线上浮不超过 64 MiB。这些数值是本次受控验收的门槛，不能单独证明没有泄漏。

缺少 NMT、PID 不符、采样不足、持续时间不足、关闭后未采样、Maven 失败或资源回收计数不符都会失败。脚本为诊断命令和整个运行设置截止时间，失败时只清理自己启动的测试 JVM 和 Maven 进程。

输出在忽略的 `target/resource-soak/` 下。CI 只保留数值 `memory.csv` 与 `summary.json`，包含源码提交、平台、运行计数和关闭结果；不上传 Maven 日志、状态文件、数据库或模型设置。数值附件保留 14 天，公开验收记录另保存结论和可复核的摘要。

## 复核附件

下载同一次工作流的四个数值附件，解压到一个空目录，例如 `target/resource-artifacts/`。从工作流详情取得完整的源码 SHA，再运行：

```powershell
python -m unittest discover -s scripts -p 'test_resource*.py'
python scripts/resource_report.py target/resource-artifacts --source-commit <完整源码SHA> --seconds 3600
```

复核命令要求 Agent / Starter 与 Windows / Linux 四个组合各出现一次，并重算原始 CSV 的稳态增长、峰值和关闭样本。它也检查 NMT 总量与堆／非堆量一致、采样覆盖稳态首尾、运行计数完整、资源归还和源码工作区干净。传入的时长应与本次工作流输入一致；验证失败时不生成通过表。可用 `--output target/resource-table.md`保存表格。

汇总一致性检查不能代替附件来源核对。请确认附件属于所记录的工作流和源码提交；这些受控数值也不能用于推断生产吞吐或数天运行的稳定性。
