# GitHub 仓库概览

从固定导航的“研发协作”进入，填写 `owner/repository` 或标准 GitHub 仓库链接，点击“接入仓库”。系统会读取仓库信息、开放 Issue、开放 PR 和默认分支的近期工作流。每条记录都能回到 GitHub 查看。

公开仓库无需凭据。需要更高额度或读取私有仓库时，可在启动进程中设置 `TRIAGE_GITHUB_TOKEN`；使用只读的仓库 Metadata、Issues、Pull requests 和 Actions 权限。Token 只参与请求，不写入数据库、网页响应或日志。仓库名称和概览保存在已有的本机数据库中，重启后继续可见。

Issue 和 PR 各最多显示 20 条。Issue 从最近 50 条开放事项中过滤 PR，属于抽样；GitHub 的 `open_issues_count` 同时包含两类事项，因此页面不会把它当作 Issue 总数。CI 显示默认分支最近 20 次工作流，失败、排队、运行、取消和缺少结论分别展示。Actions 读取被拒绝时显示“读取失败”，不计为零失败。

打开页面读取本机保存的快照。点击“更新 GitHub 状态”才请求新的数据，默认两次更新至少间隔 30 秒；超过两分钟的快照提示更新。读取仓库信息失败时保留旧快照及原时间；某一列表失败时，其余列表仍可查看。最多登记 10 个仓库。

“移除登记”只删除本机仓库记录、概览快照、CI、PR、Issue 报告与协同工作流，需在页面确认。所有 GitHub 请求均为 GET，不创建评论、修改标签或合并 PR。已提供[CI 失败日志排查](CI_TRIAGE.md)、[PR 变更审查](PR_REVIEW.md)、[Issue 分诊](ISSUE_TRIAGE.md)和[PR 协同工作流](DEVELOPMENT_WORKFLOWS.md)。

API：

| 方法 | 路由 | 用途 |
|---|---|---|
| GET | `/api/development/repositories` | 查看本机仓库登记 |
| POST | `/api/development/repositories` | 登记并读取仓库，正文为 `{"repository":"owner/name"}` |
| GET | `/api/development/repositories/{id}/health` | 查看已保存概览 |
| POST | `/api/development/repositories/{id}/refresh` | 更新 GitHub 数据 |
| DELETE | `/api/development/repositories/{id}?confirmId={id}` | 确认移除本机登记 |

接口只允许本机同源访问，写入本机状态时需要 `X-Triage-Development: 1`。GitHub 请求只访问固定 API origin，不跟随重定向；链接、描述和标题按文本显示。每次响应最多读取 1 MiB，拒绝用户提供任意 API 地址。
