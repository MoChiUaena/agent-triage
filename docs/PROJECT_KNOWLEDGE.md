# 项目知识导入与版本（K1）

在「项目源码 → 项目知识」选择已登记的本机项目或 GitHub 仓库，先预览准确目录 / 固定提交、文件清单、内容 SHA256 和排除项，再确认本机导入。在「研发协作」切换仓库可查看绑定知识，并进入管理页面。没有模型密钥也能完成全部操作。导入不调用模型，不运行文档内容，不跟随其中的 URL。

本机来源默认使用登记项目根目录，也可以另选一个绝对文档目录；范围仍是服务器核对的项目 UUID。GitHub 来源只接受登记仓库 UUID 与完整 40 位提交 SHA；读取该提交的 Git tree / blob，包含根目录 README.md / README.txt（不区分大小写）和 docs/ 下的 Markdown / TXT。不会把当前分支内容混入旧 SHA。不能确认完整的 GitHub tree、blob 大小或 Git 对象哈希时，拒绝整个预览。

## 读取边界

| 项目 | 上限 / 行为 |
| --- | --- |
| 支持类型 | UTF-8 `.md` / `.txt`；文档相对路径最多 512 字符 |
| 候选文档 | 每次最多读取 100 个符合位置 / 扩展名规则的候选；被内容检查排除的文档也消耗读取预算 |
| 内容 | 单文件 256 KiB；一次读取总量 2 MiB；最多 1000 段，每段 2000 个 UTF-16 字符，不拆代理对 |
| 遍历 | 最多 5000 条目、20 层；GitHub tree 超过条目上限会直接拒绝 |
| 排除 | 日志、构建 / 依赖目录、凭据目录与文件名、疑似凭据正文、二进制、无效 UTF-8、符号链接 / junction / 特殊文件 |
| 本机路径 | 拒绝 UNC / 网络路径、磁盘根目录、相对目录、`.` / `..` 跳转及链接目录；文档不允许逃出规范化根目录 |
| 预览 | 最多缓存 10 份、十分钟有效；仅保存清单和每文件前 240 字符，不缓存完整文档；最多同时进行 2 个预览读取 |
| 导入 | 单 worker、最多 4 个排队任务；没有线程随任务数无限增长；读取在数据库事务外进行 |
| 列表 | 每范围最多显示 100 个来源、最近 20 个任务；每来源最近 500 条文档版本；历史引用仍可按确切版本 UUID 打开 |

预览明确列出成功文件、字节 / 分段 / 遍历数、读取时间、清单摘要和排除原因；排除项最多列 100 条，更多时显示 `MORE_EXCLUSIONS`。`complete=false` 表示存在上限或读取缺口，只导入清单中的文件，不表示整个目录已导入。被规则排除的文件不在成功清单中。凭据检测是防误导入的有限规则，仍应核对预览。GitHub 传输复用现有固定来源、禁止重定向、8 秒单请求和 1 MiB 响应限制。

## 版本、更新与取消

来源保存项目 / 仓库 UUID、本机根目录或仓库、提交、revision、状态、清单摘要、导入时间和覆盖记录。原文版本保存来源、路径、标题、SHA256、完整文本、导入时提交和时间；不可变分段另存 UUID、行号、字符起止偏移、哈希和正文。偏移从 0 开始、右端不包含，行号从 1 开始。

提交导入后重新读取同一位置并核对范围、revision 和服务器生成的清单；内容或范围变化时失败，要求重新预览。`QUEUED → RUNNING → SUCCEEDED / FAILED / CANCELLED / INTERRUPTED` 可通过任务 API 查看；进度计数表示已完成重新校验的清单文档数。取消排队任务立即变成 `CANCELLED`；运行中在读取边界响应取消，单次 GitHub 请求可能等待最多 8 秒。事务提交先于取消时，任务保留 `SUCCEEDED`，页面不会假称已取消。重启将未完成任务标记 `INTERRUPTED`，不自动重试。

仅最终短事务一次性替换有效文档映射。失败、取消、过期 revision 或重启不会留下部分有效的新版本。重复导入同一来源 / 路径 / 内容复用原文及分段；修改文件生成新版本，未改文件复用，移除文件只退出当前映射。来源 revision 随成功激活递增。相同内容在不同 GitHub 提交继续复用首次保存的原文版本；当前来源记录新的导入 SHA，引用保留原文首次捕获的提交。

解绑与软删除均清除当前映射并停止新检索 / 材料发送，保留历史原文和分段。两者分别记录 `UNBOUND` / `DELETED`；重新预览并导入可以显式重新激活该来源。删除项目或仓库登记不会级联删除知识快照。历史原文 / 分段接口严格核对版本中保存的范围 UUID，登记移除后仍能打开；当前导入、来源列表、版本列表和材料发送要求范围仍登记。K2 才提供关键词检索，当前没有新增知识搜索入口。

## REST 接口

所有 `/api/knowledge` API 仅允许本机同源访问，响应 `Cache-Control: no-store`；写请求须有 `X-Triage-Knowledge: 1`。当前本机保护不是远程团队授权方案。

`scopeKind` 为 `PROJECT` / `REPOSITORY`，`scopeId` 为对应已登记 UUID；不能用任意项目名称代替。

| API | 参数与响应 |
| --- | --- |
| `POST /previews` | `{scopeKind,scopeId,directory?,commitSha?,sourceId?,revision?}` → 201 `Preview`；指定来源更新时同时传 revision |
| `GET /previews/{id}` | 有效的服务器预览；过期 / 使用后 410 |
| `POST /imports` | `{previewId}` → 202 `Job`；预览仅可提交一次，队列满时保留预览以便稍后重试 |
| `GET /sources?scopeKind=…&scopeId=…` | 当前登记范围的来源（含停用 / 软删除状态） |
| `POST /sources/{id}` | `{scopeKind,scopeId,revision,action:"UNBIND"或"DELETE",confirmed:true}` |
| `GET /sources/{id}/versions?scopeKind=…&scopeId=…` | 版本元数据，标明是否当前有效 |
| `GET /versions/{id}?scopeKind=…&scopeId=…` | 历史不可变原文 `Version`，无需仍有登记 |
| `GET /versions/{id}/chunks?scopeKind=…&scopeId=…` | 同一原文的不可变 `Chunk[]`，含位置 |
| `GET /jobs?scopeKind=…&scopeId=…` | 最近任务 |
| `GET /jobs/{id}` | 单个任务状态 / 进度，要求其范围仍登记 |
| `POST /jobs/{id}/cancel` | 请求取消；已结束任务保持原终态 |

错误：400 无效目录 / SHA / 参数或未确认影响；403 非本机同源或缺少写标记；404 范围 / 来源 / 版本不存在；409 范围不符或来源 revision 变化；410 预览过期；429 预览读取 / 缓存 / 队列达限；502 GitHub 固定版本内容不完整或校验失败。后台失败只保存安全摘要，不把异常正文、凭据或网络响应落入任务错误。

## 无密钥复现：导入 → 重复 → 更新 → 删除 → 历史

使用 JDK 21，启动独立本机演示实例：

```powershell
$env:JAVA_HOME='C:/Program Files/Java/latest/jdk-21'
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--triage.mode=DEMO --spring.datasource.url=jdbc:h2:mem:knowledge-demo;DB_CLOSE_DELAY=-1'
```

另开 PowerShell；以下只创建新临时文件，文档目录也可以换成公开 Java 项目的本机 Markdown 资料目录：

```powershell
$base='http://127.0.0.1:18080'
$root=Join-Path ([IO.Path]::GetTempPath()) ('triage-knowledge-'+[guid]::NewGuid())
New-Item -ItemType Directory -Path $root | Out-Null
Set-Content -LiteralPath (Join-Path $root 'README.md') -Value "# Project guide`nArchitecture v1" -Encoding utf8
Set-Content -LiteralPath (Join-Path $root 'Guide.java') -Value 'class Guide { String architecture() { return "demo"; } }' -Encoding utf8
$sourceHeaders=@{'X-Triage-Source'='1'}
$headers=@{'X-Triage-Knowledge'='1'}
$project=Invoke-RestMethod "$base/api/source-projects" -Method Post -Headers $sourceHeaders -ContentType application/json -Body (@{name='Knowledge demo';service='order-service';directory=$root}|ConvertTo-Json)
$scope="scopeKind=PROJECT&scopeId=$($project.id)"
function Import-Guide {
  $preview=Invoke-RestMethod "$base/api/knowledge/previews" -Method Post -Headers $headers -ContentType application/json -Body (@{scopeKind='PROJECT';scopeId=$project.id;directory=$root}|ConvertTo-Json)
  $preview.files | Format-Table path,bytes,sha256
  $preview.exclusions | Format-Table
  $job=Invoke-RestMethod "$base/api/knowledge/imports" -Method Post -Headers $headers -ContentType application/json -Body (@{previewId=$preview.id}|ConvertTo-Json)
  do { Start-Sleep -Milliseconds 200; $job=Invoke-RestMethod "$base/api/knowledge/jobs/$($job.id)" } while ($job.state -in @('QUEUED','RUNNING'))
  if ($job.state -ne 'SUCCEEDED') { throw $job.message }
}
Import-Guide
$source=@(Invoke-RestMethod "$base/api/knowledge/sources?$scope")[0]
$versions=@(Invoke-RestMethod "$base/api/knowledge/sources/$($source.id)/versions?$scope")
$old=$versions[0].id
Import-Guide # 同一文档仍是一份原文与一组分段
Set-Content -LiteralPath (Join-Path $root 'README.md') -Value "# Project guide`nArchitecture v2" -Encoding utf8
Import-Guide
$source=@(Invoke-RestMethod "$base/api/knowledge/sources?$scope")[0]
Invoke-RestMethod "$base/api/knowledge/sources/$($source.id)" -Method Post -Headers $headers -ContentType application/json -Body (@{scopeKind='PROJECT';scopeId=$project.id;revision=$source.revision;action='DELETE';confirmed=$true}|ConvertTo-Json)
Invoke-RestMethod "$base/api/knowledge/versions/$old`?$scope" # 仍是 Architecture v1
Invoke-RestMethod "$base/api/knowledge/versions/$old/chunks?$scope"
```

若已有 `order-service` 绑定项目，可直接从 `/api/source-projects` 选其 UUID 并把 `directory` 指向新文档目录，无需重复登记。浏览器打开 `/sources.html?scopeKind=PROJECT&scopeId=<UUID>#knowledge` 查看同一来源。

GitHub 路径：在研发协作页登记公开 Java 仓库，选择一个已核对的 40 位提交，POST `/previews`：`{"scopeKind":"REPOSITORY","scopeId":"<登记UUID>","commitSha":"<固定40位SHA>"}`，确认清单后用同一 `/imports` 流程。更新到另一 SHA 时传原 `sourceId` / `revision`，重新预览再导入；文档内链接不会被访问。离线固定 HTTP fixture 测试可重复验证这一链路，不依赖 GitHub 配额。

## 与共享材料 B0 的契约

`MaterialModels.Type.KNOWLEDGE` 使用 `Selection.reportId=不可变原文版本UUID`，必须明确提供 `projectId` 或 `repositoryId`，服务器核对保存范围和当前来源状态。返回不可变版本、原文 SHA256、来源路径 / 提交、带行号的保存分段以及历史原文页面链接。共享文本复用 B0 的明确字段白名单、凭据脱敏、单字段 2000 字符、数组 20 项和整份材料 16000 字符预算；裁剪会明确列出。已停用来源或已移除登记不能生成新的发送材料，但历史原文 API 仍可读。

只读 `resolve`、创建发送预览和知识导入不授予外发权限。未来 C1/A1/A2 真正发送前，消费接口必须传 `{"confirmed":true,"documentsConfirmed":true}`；旧 Java `consume(id,true)` 对知识材料也会拒绝。确认只针对该预览和其固定接收方，不保存永久分享开关，不代替源码分享授权。K1 仍不调用任何模型。

## 回归

```powershell
.\mvnw.cmd '-Dtest=KnowledgeApiTest,KnowledgeGithubApiTest,GithubKnowledgeReaderTest,KnowledgeFilesTest,KnowledgeLifecycleTest,MaterialApiTest,SendPreviewServiceTest' test
node scripts/knowledge_ui_check.js
node scripts/material_ui_check.js
node scripts/development_ui_check.js
.\mvnw.cmd verify
```

`KnowledgeApiTest` 可用 `TRIAGE_KNOWLEDGE_TEST_DB_URL` / `_USER` / `_PASSWORD` 指向独立测试 PostgreSQL；默认 H2。测试使用真实临时文件、数据库及固定本机 HTTP fixture，不执行收费调用或外部写入。
