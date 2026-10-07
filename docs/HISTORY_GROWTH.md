# 历史记录增长验证

`HistoryGrowthTest` 用 `RunRepository` 写入合成快照，再查询真实的 `HistoryRepository`，检查分页、筛选、统计、保留策略，以及关闭连接池并新建仓库后的持久化结果。测试不启动应用，也不调用模型。

常规测试在 JUnit 临时目录创建独立 H2 文件，检查 120、240、360 行。使用 JDK 21 单独运行：

```sh
./mvnw -B -ntp -Dtest=HistoryGrowthTest test
```

更大的验证最多写入 10,000 行：

```sh
./mvnw -B -ntp -Dtest=HistoryGrowthTest -Dhistory.growth.checkpoints=1000,5000,10000 test
```

Windows 将 `./mvnw` 换成 `.\mvnw.cmd`，并给每个 `-D...` 参数加引号。检查点必须为 3–5 个严格递增整数，范围为 24–10,000。

**History growth** 工作流在相关推送和拉取请求上验证两个后端，也可手动选择 5,000 或 10,000 行。CI 使用全新的 PostgreSQL 16 服务和专用 `history_growth_ci` 数据库。本地需先单独创建测试数据库，设置 `HISTORY_GROWTH_DB_URL`、`HISTORY_GROWTH_DB_USER`、`HISTORY_GROWTH_DB_PASSWORD`，再添加 `-Dhistory.growth.postgres=true`。夹具只接受无 URL 参数的 `jdbc:postgresql://localhost:PORT/history_growth_ci` 或 `127.0.0.1` 形式，端口为 1–65535，数据库须为 PostgreSQL 16、UTF-8。它创建随机私有 schema，只删除自己创建的 schema，不读取应用的共享数据库变量。

每 12 行覆盖截止时间之前的 QUEUED、RUNNING，以及四种终态在截止时间两侧和恰好到达截止时间的记录。数据包含两个服务、端点与整服务记录、完整/部分/缺失模型用量和相同时间戳。快照有 4/8/16 个事件、2/4/8 条证据，以及确定生成的 160/512/2,048 字符合成摘要和数字请求指标。这些有界数据只用于存储测试，不是业务请求录制，也不代表真实载荷大小。

成功运行输出 `target/history-growth/h2.csv`，启用 PostgreSQL 时另有 `postgres.csv`；小规模回归输出 `h2-small.csv`。工作流只上传 CSV。每个文件有表头和 28 列，数据单元格全为数字：

| 字段 | 含义 |
| --- | --- |
| `backend` | 1 为 H2，2 为 PostgreSQL |
| `checkpoint_rows`、`phase`、`rows` | 写入检查点；阶段 0 为增长、1 为清理后、2 为重开后；当前逻辑行数 |
| `payload_bytes`、`min_payload_bytes`、`max_payload_bytes` | 实际存储 JSON 的 UTF-8 总字节数、单行最小值和最大值 |
| `physical_bytes` | H2 完整 `.mv.db` 文件；PostgreSQL 的 `triage_runs` 表、索引及 TOAST，总计口径不同 |
| `projected_rows` | 已持久化历史投影的行数 |
| `load_ns` | 累计加载耗时，含构建合成记录、随机摘要生成、序列化和每批最多 100 行的事务写入 |
| `deleted_rows`、`cleanup_ns` | 删除行数和清理耗时；重开阶段沿用本次清理记录 |
| `page_*_ns`、`cursor_page_*_ns`、`filtered_page_*_ns`、`statistics_*_ns`、`filtered_statistics_*_ns` | 首页面、游标第二页、筛选页面、统计、筛选统计的 `min`/`median`/`max` 纳秒耗时 |
| `cursor_page_samples` | 存在第二页时为 5；为 0 时未采集游标耗时，三个游标耗时字段均为 0 |

查询先预热一次，再采样五次。24/48/72 行回归检查没有第二页的边界，以及清理后回到单页的情况。清理前、清理后和重开后均完整遍历游标，检查无遗漏和重复。耗时只描述本次机器和缓存状态，不设置绝对速度门槛。

保留策略应减少逻辑行数和载荷字节，并保留旧的活动记录、近期终态记录和截止边界。数据库可能保留已分配空间供复用，物理文件不必缩小；这不构成内存或存储泄漏的证据。验证范围是仓库持久化，不覆盖进程被强杀后的崩溃恢复、浏览器/API 流程或生产容量。

已归档的 10,000 行双后端运行及限制见 [2026-10-07 验证记录](validation/2026-10-07-history-growth.md)。
