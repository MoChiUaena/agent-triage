# 用户确认的项目记忆（M1/M2）

“项目源码与资料”页的“项目记忆”保存本机历史经验，支持手工候选和从任务追问消息逐字保存候选。无需模型或 API Key 即可管理；M2 增加按问题的本机关键词召回，召回结果只在用户显式选择并单独确认后才进入模型发送。记忆没有向量检索、自动跨任务注入、后台分析或自动共享功能。原报告和对话 JSON 不会因保存候选或召回而改变。

## 身份、依据与边界

每条记忆绑定登记的 PROJECT UUID 或 REPOSITORY UUID，创建后不能移动范围。项目服务由服务器读取当前登记，仓库不推断服务；同名项目、同服务重新登记产生的新 UUID 不会继承旧记忆。仅有 SERVICE 身份的旧对话不能猜测今日绑定项目，页面会说明原因并禁用候选按钮。写操作要求登记仍存在；原登记删除后，可通过原始范围和记忆 ID 打开历史。

同一 PROJECT UUID 的服务绑定发生变化时，旧对话不能派生成今日新服务候选，旧记忆不能编辑或重新确认；原服务身份仍保留在历史和列表中。项目仍登记时允许停用/软删除撤回旧记忆。显式 service 筛选同时要求当前项目绑定匹配，并只返回保存服务相符的记录；省略 service 可在准确 UUID 下找到旧经验并撤回。

用户可填写最多 2000 字符的依据陈述，明确标注为“用户陈述，未经独立验证”。另可选择最多 3 个已经保存的 B0 报告或 K2 准确版本/分段 ID。服务器解析、核对范围并保存不可变快照，忽略客户端提供的正文、哈希或快照。不会读取任意源码路径或新 GitHub 数据。编辑时未改动引用即保留已保存历史快照；新增或改动的引用重新解析校验。原报告、知识来源或登记被移除后，保存的依据仍可读。明确确认审阅的是历史快照，不意味着来源仍有效或产生了新的运行观察。

消息候选必须显式选择本对话的 1–3 条已保存消息。默认正文为选择顺序下的逐字内容；超过 4000 字符时正文只取前 4000 字符，依据明确注明裁剪，并保留完整选中消息、消息/对话 ID、引用和对话原始报告快照。依据整体超限时拒绝保存，绝不静默丢弃出处。用户可编辑候选后再确认，编辑也保留对话原始出处。文档或消息里的“confirmed/已确认”文字不会使候选自动生效。

## 生命周期与容量

创建和编辑均产生 CANDIDATE。只有显式勾选审阅范围、版本、正文、完整依据和 SHA256，并提交准确 revision、bodySha256、confirmed=true 才会追加 CONFIRMED 版本及确认时间/出处。默认列表只展示当前 CONFIRMED；候选、停用、删除和全部状态需显式切换筛选。DISABLED 可明确审阅后再次确认，或编辑成新候选。DELETED 是软删除终态，仅可阅读历史。

每次正文、主题、依据或状态改变都会追加版本，旧版本不会覆盖。编辑会清除确认，包括编辑已停用记忆。并发修改与陈旧确认使用数据库 optimistic revision 条件原子拒绝，哈希不匹配、未勾选或范围不符也会拒绝。确认仅代表用户审阅，不证明内容事实正确、当前仍适用或允许对外分享；M1 从不自动加入 C1/模型上下文。

容量包括软删除记录：总计 1000 条，每范围 100 条；主题 120、正文 4000、用户陈述 2000 字符，显式引用最多 3 条，序列化依据最多 48000 UTF-16 字符。每条常规最多 20 个版本（状态操作也占用）；达到上限后仅允许额外的停用及软删除撤回，最多总计 22 版，不能利用重复停用重新确认/编辑绕过上限。列表默认 20 条、最多 100 条，版本历史最多 22 条。超限返回明确 400/429，不静默裁剪保存依据。

## 召回、适用性与冲突（M2）

召回按登记范围（PROJECT/REPOSITORY）和问题关键词在本机进行：问题中的所有词（大小写不敏感，连续匹配）都须命中主题或正文才返回，结果按命中分数和更新时间排序，一次最多返回 10 条（默认 5），扫描上限为该范围全部 100 条已确认记忆。只有状态为 CONFIRMED 的当前版本参与召回；候选、已停用、已删除或确认后被编辑出确认状态的记忆立即退出，停用后无需等待。

召回逐条返回适用性提示，仅核对登记绑定与依据版本，不检测代码行为变化：项目服务绑定与保存时不同、依据报告版本已变化或被移除、依据知识版本不再有效时分别注明“请先核对适用性”。同一范围存在多条同主题（忽略大小写与多余空白）已确认记忆时，召回同时展示冲突双方来源与确认时间，由用户在记忆面板停用其一或改写后重新确认，保留或替代关系由用户决定，系统不自动合并或淘汰。

对话追问可把召回结果中最多 3 条加入本次问题：选择绑定准确记忆 ID 与 revision，预览正文包含完整记忆内容与来源说明；发送须在通用确认和资料确认之外单独勾选 memoriesConfirmed。已确认记忆是用户审阅的历史经验，未经独立验证、不代表当前运行观测或 GitHub 状态，运行观测与当前状态优先。发送前后服务器重新核对所选 revision 仍为当前 CONFIRMED 版本；记忆被编辑、停用或删除后，旧预览立即失效并要求重新召回。模型回答通过 `memory:{id}:{revision}` 引用标注实际使用的记忆；历史消息中保存的记忆引用原文不随后续编辑或停用改变。

## 本机 API

所有写请求均为 `Content-Type: application/json` 并携带 `X-Triage-Memory: 1`。接口只允许本机 loopback、同源访问，并使用 no-store；这不是远程认证服务。

| 方法 | 路径 | 参数 / 含义 |
| --- | --- | --- |
| GET | `/api/memories/scopes` | 当前 PROJECT/REPOSITORY 选项 |
| GET | `/api/memories` | 必填 scopeKind、scopeId；可选 service、status（默认 CONFIRMED）、limit（默认 20） |
| POST | `/api/memories` | scopeKind、scopeId、topic、content、note、references；service 仅可匹配服务器绑定 |
| GET | `/api/memories/{id}` | 必填 scopeKind、scopeId，读取当前不可变版本 |
| GET | `/api/memories/{id}/history` | 同范围，返回有界版本摘要 |
| GET | `/api/memories/{id}/versions/{revision}` | 同范围，读取准确历史版本及依据，不要求登记仍存在 |
| POST | `/api/memories/{id}/edit` | 创建参数及准确 revision；references 为 null/缺失时保留已有快照 |
| POST | `/api/memories/{id}/confirm` | scopeKind、scopeId、revision、bodySha256、confirmed=true |
| POST | `/api/memories/{id}/disable`、`/{id}/delete` | scopeKind、scopeId、准确 revision |
| POST | `/api/memories/conversation-candidates` | conversationId、messageIds；范围与快照只从服务器保存对话派生 |
| POST | `/api/memories/recall` | scopeKind、scopeId、question（1—4000）、limit（1—10，默认 5）；仅返回 CONFIRMED 当前版本、命中片段、适用性提示与同主题冲突 |

报告引用为 `{ "type": "SERVICE|PR|ISSUE|CI|WORKFLOW", "reportId": "UUID" }`；知识分段为 `{ "type": "CHUNK", "versionId": "UUID", "chunkId": "UUID" }`。历史页可用 `/sources.html?scopeKind=PROJECT&scopeId=UUID&memory=UUID&memoryRevision=1#memory-panel`，省略 memoryRevision 则打开当前版本。移除登记后此链接仍可只读打开，不能写入或确认。

## 无密钥手工查看

1. 使用 JDK 21，执行 `./mvnw.cmd -DskipTests package`（Linux/macOS 为 `./mvnw`），再在自己的终端前台运行 `java -jar target/agent-triage-0.12.0.jar --triage.mode=DEMO`。默认页面 `http://127.0.0.1:18080/sources.html`。自动验证使用 TestRestTemplate 随机端口，不额外启动后台应用。
2. 在源码页选择已登记服务（例如 `order-service`），再登记现有 `sample-service` 目录（例如本仓库绝对路径下的 `sample-service`）或自己的 Java 项目；登记后可按需要编辑并解绑服务。登记后“项目记忆”的范围下拉立即可选，也可点“刷新范围 / 记忆”。模型分享授权保持关闭，资料只在本机查看。
3. 选择准确范围，点“新建候选”，填主题、经验和用户陈述，保存。正文和依据呈现在精确版本视图；勾选标明 UUID、版本和哈希的审阅框，点“确认已审阅此版本”。默认有效列表可见此条。
4. 修改正文并保存，核对新状态 CANDIDATE、确认被清除。切换“候选”筛选，重新打开并确认。点“停用此记忆”，有效列表不再包含，切换“已停用”仍可查看。点击版本历史可读旧正文和依据；软删除后历史仍保留。
5. 消息候选：从已经保存了项目或仓库身份的报告进入“继续追问”，保存一个问题（无需调用模型），在消息上点“保存为记忆候选”，再点“打开候选，编辑并审阅确认”。新项目报告需先绑定相应服务、在本机源码分析流程中保存含 sourceAnalysis.projectId 的报告；已有仓库研发报告也可用。仅服务身份的旧报告不会被补绑，按钮会禁用并解释。候选打开范围为原 UUID，仍需明确确认。
6. 为验证出处独立保存，可先添加已有报告或知识准确分段作为依据，再移除原来源，重开记忆版本核对保存的历史原文。切换范围或编辑会清除待提交确认，旧异步响应不会覆盖新范围/草稿。
7. 召回链路（M2）：确认至少两条同主题记忆与一条候选，进入已保存项目/仓库身份报告的“继续追问”，填写包含记忆关键词的问题，点“按当前问题召回已确认项目记忆”。核对命中片段、同主题冲突提示与“打开记忆面板核对与纠错”链接；勾选最多 3 条后预览，发送前须单独勾选记忆外发确认。把所选记忆停用后重试发送，会提示记忆已退出确认状态，历史消息中的记忆引用原文不变。

## 验证

`./mvnw -Dtest=MemoryStoreTest,MemoryApiTest,ConversationApiTest test` 覆盖生命周期、并发/陈旧确认、同名同服务身份隔离、服务旧对话拒绝、服务器解析报告/准确分段、来源移除后的历史、关键词召回（CONFIRMED 过滤、同主题冲突、停用立即退出、知识版本漂移提示）及对话选择记忆的独立外发确认与停用冲突。`node scripts/memory_ui_check.js` 与 `node scripts/conversation_ui_check.js` 验证安全文本、候选按钮、范围/草稿/确认异步隔离、召回渲染与独立确认门控。

`MemoryApiTest` 可通过 `TRIAGE_MEMORY_TEST_DB_URL`、`TRIAGE_MEMORY_TEST_DB_USER`、`TRIAGE_MEMORY_TEST_DB_PASSWORD` 指向专用 PostgreSQL 数据库；不能和其他拥有独立 provider 密钥文件的测试共用数据库。CI 创建独立 `triage_memory`，同时保留 conversation 专库。V15 使用 H2/PostgreSQL 兼容 SQL；记忆版本仅关联自己的 head，不对报告、知识、对话或登记建立级联外键。
