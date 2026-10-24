# 项目知识导入、检索与引用（K1 / K2 / K3）

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

解绑与软删除均清除当前映射并停止新检索 / 材料发送，保留历史原文和分段。两者分别记录 `UNBOUND` / `DELETED`；重新预览并导入可以显式重新激活该来源。删除项目或仓库登记不会级联删除知识快照。历史原文 / 分段接口严格核对版本中保存的范围 UUID，登记移除后仍能打开；当前导入、来源列表、版本列表、检索和材料发送要求范围仍登记。

## 当前知识关键词检索（K2）

在同一知识分区选择项目 / 仓库，输入关键词，可按 Markdown / TXT 过滤。项目还可选其当前绑定服务；服务只是服务器核对的项目绑定，不会跨项目扩展范围，也不从名称推断项目 UUID。仓库不接受服务筛选。无需密钥，不调用模型、不执行文档内容或链接，也不是语义 / 向量检索。

检索词 1–200 个 UTF-16 字符，去除空白后需含文字或数字。按 Unicode 字母 / 数字 / 下划线分词，大小写不敏感；中文连续词句按子串匹配，例如 `连接池`。多个词都须出现在同一段正文、标题或路径中。每词标题命中 8 分、路径 4 分、正文每次命中 2 分（每词最多计 3 次），按总分降序，再按路径、来源 UUID、版本 UUID、段序、分段 UUID 排序。无分页；相同数据和参数的顺序稳定。

只读 `ACTIVE` 来源和当前文档映射；旧版本、移除文件、解绑 / 删除来源均不参加新检索。每次按来源、路径、版本、段序固定扫描最多 2000 个段（每段最多 2000 字符），再选前 1–20 个结果，默认 10。`scannedChunks` / `availableChunks` 和 `truncated` 标记扫描覆盖；达限后可能遗漏后续高分匹配，不能把无结果视为全范围没有资料。只覆盖成功导入的当前资料，原始目录的排除、读取上限与缺口仍见来源预览。

结果返回不可变 `chunkId`、`versionId`、`sourceId`、保存 `scope`、标题、最多 360 字符的匹配邻近摘要、路径、固定 GitHub 提交来源 URL（本机文件仅返回路径）、原文和分段 SHA256、行号 / 偏移、导入时间、提交、分数及 `recallMethod=KEYWORD`。检索响应不带完整段正文；点「查看分段原文」重新从服务器校验版本、分段与保存范围，显示准确的最多 2000 字符原段及原始行号，再可打开同一版本全文。`/sources.html?scopeKind=PROJECT&scopeId=<UUID>&version=<版本UUID>&chunk=<分段UUID>#knowledge` 可直接打开旧引用，即使登记已删除；不得改用当前文档替代旧内容。

公开 Java 入口为 `KnowledgeSearchService.search(Scope, query, docType, service, limit)` 与 `chunk(Scope, versionId, chunkId)`；下游只能提交服务器保存的身份，不能用任意调用方文本冒充引用。K2 本批只交付检索和引用，任务追问 / 研发分析的消费集成留给 C1 / A1 等真实调用方，尚未完成跨功能消费者验收。内置排障规则与运行证据门槛保持原契约；项目文档经验不能替代当前观测，也不授予源码或资料外发权限。

## 可选向量与混合检索（K3）

K3 是纯可选增强：不配置 Embedding 服务时，关键词检索与一切既有功能保持不变。模型设置页的「检索辅助服务」分区与聊天模型相互独立，各保存一条可选配置：Embedding 服务（OpenAI 兼容 `/embeddings` 协议）与 Rerank 重排服务（`POST {baseUrl}/rerank`，请求 `{model, query, documents}`、响应 `results:[{index, relevance_score|score}]`）。地址须为 HTTPS 或本机 HTTP，不含接口路径；密钥复用本机加密存储，页面不回显。页面标注发送范围：建立索引时发送当前有效知识分段正文，混合检索时发送检索词，启用重排时发送候选摘录（每段最多 1000 字符）。测试连接会各发起一次短请求，返回向量维度与已知 tokens，可能产生费用。

向量索引按范围建立。知识页「向量索引」先预览发送范围（分段数、来源数、模型、服务地址与截断说明），确认 documentsConfirmed 后单线程执行：单次最多 2000 段（与关键词扫描上限一致）、每批 16 段、进度可查、取消生效，服务重启标记任务中断不自动重试。向量以 JSON 浮点数组存入 PostgreSQL / H2 平表，相似度在本机计算余弦，不依赖数据库扩展。每段记录配置指纹（baseUrl+model 的 SHA256）与维度；修改模型或地址后旧索引自动失效，重新建立索引时清理其他指纹。任务记录已知 Embedding 用量（调用次数与 tokens，由服务返回时才记录）。知识更新、解绑或删除后索引不自动重建；索引不完整时混合检索会在覆盖说明中写明。

`POST /api/knowledge/hybrid-search` 在本机融合两个候选池：本机关键词池（复用 K2 规则，上限 100）与向量池（当前指纹索引内余弦排序，上限 100），按 RRF（k=60）融合去重排序，逐条标注 `matchedBy`（KEYWORD / VECTOR）与融合分数；引用仍指向原始不可变分段。请求须确认 `confirmed`（检索词发送至 Embedding 服务）；`rerank=true` 且已配置 Rerank 时，须另行确认 `documentsConfirmed`（最多 20 段融合候选摘录发送至 Rerank 服务）。重排按分数重排候选，服务未覆盖的候选保持融合顺序；未请求、未配置、调用失败或响应不完整时回退融合排序并在 `rerank.reason` 说明。

向量阶段在以下情况自动回退关键词并逐项说明原因（`vector.status` = UNAVAILABLE / FAILED 与 `vector.reason`）：未建立索引（此时不发送任何请求）、Embedding 配置已变化需重建、查询维度与索引不一致、当前筛选类型内无已索引分段、Embedding 服务调用失败或超时。`docType` 与 `service` 筛选在关键词与向量两侧一致生效。不宣称未经测量的准确率提升；固定问题集对照见验收记录，检索经验仍不替代运行观测与内置证据门槛。

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
| `GET /search?scopeKind=…&scopeId=…&query=…&docType=ALL&limit=10&service=…` | `docType` 可选 `ALL` / `MARKDOWN` / `TEXT`；`service` 可选且须为当前项目绑定。返回 `SearchResult`，含 results / 扫描覆盖 / 截断标记；有效范围无匹配返回 200 和空数组 |
| `GET /versions/{versionId}/chunks/{chunkId}?scopeKind=…&scopeId=…` | 仅返回指定版本中指定持久段的引用与准确 `text`，历史可读；错版本段 404、错保存范围 409 |
| `GET /jobs?scopeKind=…&scopeId=…` | 最近任务 |
| `GET /jobs/{id}` | 单个任务状态 / 进度，要求其范围仍登记 |
| `POST /jobs/{id}/cancel` | 请求取消；已结束任务保持原终态 |
| `POST /embeddings/preview` | `{scopeKind,scopeId}` → 201 向量索引发送范围预览（十分钟有效，最多缓存 10 份）；需已配置 Embedding 服务 |
| `POST /embeddings/jobs` | `{scopeKind,scopeId,previewId,documentsConfirmed}` → 202 有界向量索引任务；`documentsConfirmed=false` 拒绝 |
| `GET /embeddings/jobs?scopeKind=…&scopeId=…` / `GET /embeddings/jobs/{id}` | 任务状态、进度、已知 Embedding 用量（次数与 tokens） |
| `POST /embeddings/jobs/{id}/cancel` | 请求取消；完成时按当前指纹清理其他配置的旧向量 |
| `POST /hybrid-search` | `{scopeKind,scopeId,query,docType?,service?,limit?,confirmed,rerank?,documentsConfirmed?}` → `HybridResult`（逐条 citation / matchedBy / fusedScore / rerankScore，vector 与 rerank 状态说明）；未确认发送或重排确认缺失返回 400 |

设置侧新增 `GET /api/settings/search-models`、`PUT/DELETE /api/settings/search-models/{embedding|rerank}`、`POST …/{kind}/test`，受 `/api/settings` 同源与 `X-Triage-Settings: 1` 规则保护。

错误：400 无效目录 / SHA / 参数或未确认影响；403 非本机同源或缺少写标记；404 范围 / 来源 / 版本不存在；409 范围不符或来源 revision 变化；410 预览过期；429 预览读取 / 缓存 / 队列达限；502 GitHub 固定版本内容不完整或校验失败；检索 / 精确段查询的 503 表示存储不可用，不能解释为无匹配。后台失败只保存安全摘要，不把异常正文、凭据或网络响应落入任务错误。

## 无密钥复现：导入 → 检索 → 原文 → 更新 → 当前检索 / 历史引用

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
$search=Invoke-RestMethod "$base/api/knowledge/search?$scope&query=Architecture&docType=MARKDOWN&limit=10"
$search | Select-Object recallMethod,scannedChunks,availableChunks,truncated
$hit=$search.results[0]
$citationUrl="$base/api/knowledge/versions/$($hit.versionId)/chunks/$($hit.chunkId)?$scope"
$original=Invoke-RestMethod $citationUrl
$original | Select-Object title,path,startLine,endLine,sha256,chunkSha256,text
# 浏览器中打开 "$base$($hit.originalUrl)" 可看准确旧段，再打开该版本全文
$source=@(Invoke-RestMethod "$base/api/knowledge/sources?$scope")[0]
$versions=@(Invoke-RestMethod "$base/api/knowledge/sources/$($source.id)/versions?$scope")
$old=$versions[0].id
Import-Guide # 同一文档仍是一份原文与一组分段
Set-Content -LiteralPath (Join-Path $root 'README.md') -Value "# Project guide`nArchitecture v2" -Encoding utf8
Import-Guide
$current=Invoke-RestMethod "$base/api/knowledge/search?$scope&query=v2"
$oldTerm=Invoke-RestMethod "$base/api/knowledge/search?$scope&query=v1" # results 为空
$current.results | Select-Object path,versionId,sha256,excerpt
Invoke-RestMethod $citationUrl # 准确原段仍为 Architecture v1；versionId / chunkId / 哈希不变
$source=@(Invoke-RestMethod "$base/api/knowledge/sources?$scope")[0]
Invoke-RestMethod "$base/api/knowledge/sources/$($source.id)" -Method Post -Headers $headers -ContentType application/json -Body (@{scopeKind='PROJECT';scopeId=$project.id;revision=$source.revision;action='DELETE';confirmed=$true}|ConvertTo-Json)
Invoke-RestMethod "$base/api/knowledge/search?$scope&query=Architecture" # 删除后 results 为空
Invoke-RestMethod $citationUrl # 历史段仍可打开
Invoke-RestMethod "$base/api/knowledge/versions/$old`?$scope" # 仍是 Architecture v1
Invoke-RestMethod "$base/api/knowledge/versions/$old/chunks?$scope"
```

若已有 `order-service` 绑定项目，可直接从 `/api/source-projects` 选其 UUID 并把 `directory` 指向新文档目录，无需重复登记。浏览器打开 `/sources.html?scopeKind=PROJECT&scopeId=<UUID>#knowledge` 查看同一来源。

GitHub 路径：在研发协作页登记公开 Java 仓库，选择一个已核对的 40 位提交，POST `/previews`：`{"scopeKind":"REPOSITORY","scopeId":"<登记UUID>","commitSha":"<固定40位SHA>"}`，确认清单后用同一 `/imports` 流程。更新到另一 SHA 时传原 `sourceId` / `revision`，重新预览再导入；文档内链接不会被访问。离线固定 HTTP fixture 测试可重复验证这一链路，不依赖 GitHub 配额。

## 与共享材料 B0 的契约

`MaterialModels.Type.KNOWLEDGE` 使用 `Selection.reportId=不可变原文版本UUID`，必须明确提供 `projectId` 或 `repositoryId`，服务器核对保存范围和当前来源状态。返回不可变版本、原文 SHA256、来源路径 / 提交、带行号的保存分段以及历史原文页面链接。共享文本复用 B0 的明确字段白名单、凭据脱敏、单字段 2000 字符、数组 20 项和整份材料 16000 字符预算；裁剪会明确列出。已停用来源或已移除登记不能生成新的发送材料，但历史原文 API 仍可读。

现有 K1 `KNOWLEDGE` 材料选择的是整份不可变原文版本，并不表示已选择 K2 命中段。本批没有扩展 B0 表单、缓存或发送预览。未来 C1 发送端必须先扩展准确的分段选择 / 预览，以保存的版本、段 UUID 与范围重新核对，只消费用户选中的段；不得悄悄用整文前 16000 字符替代命中段，也不得把调用方提交的原始文本当可信引用。

只读 `resolve`、创建发送预览和知识导入不授予外发权限。未来 C1/A1/A2 真正发送前，消费接口必须传 `{"confirmed":true,"documentsConfirmed":true}`；旧 Java `consume(id,true)` 对知识材料也会拒绝。确认只针对该预览和其固定接收方，不保存永久分享开关，不代替源码分享授权。K1 仍不调用任何模型。

## 回归

```powershell
.\mvnw.cmd '-Dtest=KnowledgeSearchApiTest,KnowledgeSearchServiceTest,KnowledgeApiTest,KnowledgeGithubApiTest,GithubKnowledgeReaderTest,KnowledgeFilesTest,KnowledgeLifecycleTest,KnowledgeEmbeddingApiTest,KnowledgeHybridSearchApiTest,HybridFusionTest,EmbeddingSettingsApiTest,MaterialApiTest,SendPreviewServiceTest' test
node scripts/knowledge_ui_check.js
node scripts/knowledge_search_ui_check.js
node scripts/embedding_ui_check.js
node scripts/material_ui_check.js
node scripts/development_ui_check.js
.\mvnw.cmd verify
```

`KnowledgeApiTest` / `KnowledgeSearchApiTest` / `KnowledgeEmbeddingApiTest` / `KnowledgeHybridSearchApiTest` 可用 `TRIAGE_KNOWLEDGE_TEST_DB_URL` / `_USER` / `_PASSWORD` 指向独立测试 PostgreSQL；默认 H2。`EmbeddingSettingsApiTest` 走 `TRIAGE_TEST_DB_URL`。CI PostgreSQL 作业同时运行以上测试类；向量存储为普通表，PostgreSQL 16 与 H2 均验证，不依赖扩展。模型协议由本机 HTTP 桩覆盖，不执行收费调用。测试使用真实临时文件、数据库及固定本机 HTTP fixture，不执行收费调用或外部写入。
