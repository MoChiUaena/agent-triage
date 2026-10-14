# PR 变更审查

在“研发协作”中选择仓库，点击 PR 条目的“审查变更”，或输入 PR 编号。页面先显示当前 base/head SHA；点击“生成审查报告”后读取差异、提交检查和正文引用的 Issue。报告保存在本机，最近 20 份可从历史打开。

差异来自固定 SHA 的 GitHub compare。读取前后都会核对 PR 的 base/head，提交变化时不保存混用版本的结果。新增行链接到 head，删除行链接到 compare 返回的 merge-base；重命名文件的删除行使用原文件名。报告保留生成时间，之后的推送或 CI 更新不会自动改写历史。

当前在本机按规则检查部分 Java 访问控制、外部进程调用、公开方法删除、SQL 结构删除和数据库自动建表配置。每条提醒包含文件、行号、脱敏片段和核查建议。它不是完整静态分析，也没有接入模型建议；未命中规则不代表没有风险，报告不批准合入。

CI 只读取 head SHA 上最新的 Checks 与 Status，各最多 50 项。没有检查、未完成、失败、取消、缺少成功结论和读取不完整分别显示。已读取的检查通过不代表满足分支保护，也不包含 GitHub 生成的合并提交检查。Issue 只读取 PR 正文中的同仓库 `#编号` 引用，最多五个；不推断问题已解决，不读取跨仓库关系。

每次最多检查 100 个文件、200,000 个补丁字符，单文件最多 60,000 字符或 6,000 行，保留前 12 条提醒。GitHub compare 的文件列表本身最多 300 个。文件缺失、补丁省略、行数不完整或达到限额时，报告列出缺口。没有增删行的文件只显示元数据。单次 HTTP 响应仍受 1 MiB 与八秒期限限制，超过时会提示读取失败。

完整补丁只在内存中处理，不写入数据库；报告只保存文件元数据和选中的脱敏证据。Token 沿用启动配置，不进入报告、页面或日志。公开仓库可不配凭据；读取私有仓库需仓库 Metadata、Contents、Pull requests、Issues、Checks 与 Commit statuses 的只读权限，CI 日志排障还需 Actions 只读权限。移除仓库登记时会一并删除本机 PR 与 CI 报告，不修改 GitHub 内容。

| 方法 | 路由 | 用途 |
|---|---|---|
| GET | `/api/development/repositories/{id}/pr/pulls/{number}` | 读取 PR 当前版本 |
| POST | `/api/development/repositories/{id}/pr/reports` | 生成报告，正文为 `{"number":7,"baseSha":"40位SHA","headSha":"40位SHA"}` |
| GET | `/api/development/repositories/{id}/pr/reports` | 最近 20 份报告 |
| GET | `/api/development/pr/reports/{reportId}` | 已保存报告 |

接口沿用本机同源保护；POST 需要 `X-Triage-Development: 1`。所有 GitHub 请求均为 GET，不发表评论或合并 PR。
