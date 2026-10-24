# 已保存报告的连续追问与本机上下文预算（C1 / C2）

任务追问支持 SERVICE、PR、ISSUE、CI、WORKFLOW 五类已保存报告。每个对话都有独立 UUID，创建时保存原问题、报告材料、范围和版本的不可变快照。追问消息、调用状态及引用分别保存，不修改原报告，不获取新观测，不刷新 GitHub。需要当前运行事实或新 PR 版本时，请明确发起新任务。

## 一条可复现的使用链路

1. 在“项目源码”的知识管理区导入 Markdown/TXT，或在研发协作页导入仓库固定提交中的文档。导入只在本机建立资料版本，不授权模型外发。
2. 生成一份与该项目或仓库绑定的服务排查、PR、Issue、CI 或工作流报告。在结果面板选择“继续追问”，确认报告 ID，再点“从报告开始追问”。历史记录也有同一入口。
3. “历史与统计 → 任务追问”显示原报告版本、范围、模型是否可用。展开“创建时的原报告快照”即可查阅原问题、证据和规则说明。原报告或登记被删除后，此快照仍可打开。
4. 填写问题，例如“哪些证据支持这个结论，下一步还缺什么？”没有模型时，点“保存问题并查看原依据”。只保存用户问题，调用记录明确显示“未调用模型”，不会生成伪造的助手回答。
5. 在对话内输入资料关键词并本机检索，勾选最多三个具体分段。界面显示路径和行范围；模型只会收到所选准确分段，不会替换为整份文档。旧服务报告若未保存项目身份，只能使用服务材料，不能套用当前同名项目的资料。
6. 在模型设置启用服务，回到对话勾选本次使用模型，点击“预览本次模型发送内容”。逐项核对接收方、模型/配置版本、系统指令、准确问题、纳入历史、原报告、资料分段和省略说明，以及正文 SHA256。源码、完整日志、补丁不在此授权范围内。
7. 勾选本次正文外发确认。若本次选段或纳入的历史问题/回答含项目资料，还必须单独勾选资料外发确认；本次没有新增分段也不能省略此确认。然后确认发送。
8. 回答下点“查看引用”，查看当时保存的报告/分段正文、版本、行号、偏移与哈希。可以打开历史来源核对。取消、错误或格式/引用校验失败会保留原报告和尝试状态；重新预览后可重试。
9. 刷新页面或从已保存追问列表重新打开，原报告、消息和引用仍在。取消后的迟到回答不会发布；应用重启将未完成尝试标记为中断，不自动重复付费调用。

模型内容、文档、历史回答均是不可信上下文。模型回答只用于解释依据、说明缺口和细化检查建议，不构成新观测或已确认根因。

## API

所有接口仅允许本机同源访问；写请求需要 `Content-Type: application/json` 和 `X-Triage-Conversation: 1`。响应不缓存。下列 `origin` 的范围由服务端根据保存报告解析，客户端可附加的 projectId/repositoryId/service 仅用于一致性检查，不能改变范围。

| 方法与路径 | 请求与结果 |
| --- | --- |
| `POST /api/conversations` | `{ "origin": { "type": "PR", "reportId": "UUID" } }`，201 返回对话详情 |
| `GET /api/conversations` | 返回已保存对话列表（最多 1000 个，按更新时间排序） |
| `GET /api/conversations/{id}` | 不可变 `original`、`revision`、`activeAttempt`、`messages`、`attempts`、当前 `model`、保存的 `budget`、本机历史 `summary` 与边界说明 |
| `POST /api/conversations/{id}/references` | `{ "revision": 0, "question": "依据是什么？" }`，200 保存用户问题和 `REFERENCE_ONLY` 尝试，不产生助手消息 |
| `POST /api/conversations/{id}/budget` | `{ "revision": 0, "budget": { "maxInputCharacters": 48000, "recentMessages": 8, "summaryCharacters": 3000 } }`，200 原子保存预算并递增 revision，活动调用中拒绝修改 |
| `POST /api/conversations/{id}/context` | 与 previews 相同的 revision/question/selectedChunks 请求，200 直接返回 Context；无需模型，不创建/消费 B0 预览、不产生调用记录 |
| `POST /api/conversations/{id}/previews` | `{ "revision": 0, "question": "下一步？", "selectedChunks": [{ "versionId": "UUID", "chunkId": "UUID" }] }`，201 返回 `{context, preview}` |
| `POST /api/conversations/{id}/attempts` | `{ "previewId": "UUID", "confirmed": true, "documentsConfirmed": true }`，202 返回已受理状态；发送仅使用该预览的问题和正文，不接收替换问题 |
| `POST /api/conversations/{id}/attempts/{attemptId}/cancel` | `{}`，200 返回取消后的详情；取消不代表外部服务未计费 |

`preview` 复用 B0 的接收方、不可变材料、完整正文、SHA256、时间和省略字段。正文包含真正发送的系统/用户消息及固定输出限制。`context` 显示纳入的历史和引用清单，`documentsRequired` 明确资料派生历史是否需要额外授权。预览绑定对话 UUID、revision、准确问题、历史、引用、接收方和配置版本，只能消费一次。原报告/源码版本、当前资料版本/来源有效性或模型改变后必须重新预览。历史知识引用可以独立阅读，但不允许把失效资料用于新的发送。

用户消息带有 attemptId；助手消息仅在该尝试仍为当前活动尝试时原子发布。引用 ID 为 `report:<report UUID>` 或 `chunk:<chunk UUID>`，引用对象保存原报告或 K2 准确分段的不可变快照。未知引用、重复/额外 JSON 字段、工具调用、截断和非完整 JSON 输出都会被拒绝。模型只可返回 `{answer,evidenceIds}`。

## 固定边界与状态

- 最多保存 1000 个对话，每对话最多 80 条消息。模型准入预留一条助手消息空间。同一对话只允许一个活动尝试，乐观 revision 防止双击和并发消息交叉。
- 问题 1—4000 字符；模型回答最多 8000 字符，输出 tokens 上限为 2048 与当前模型配置上限中的较小值；最多 3 个本次选段。精确上下文最多 64000 字符。
- 每个对话保存独立预算，默认 `maxInputCharacters=48000`、`recentMessages=8`、`summaryCharacters=3000`。允许范围分别为 8000—64000、2—12、0—6000；摘要上限必须小于总上限，0 明确禁用较旧摘录。V13 旧对话通过 V14 可空预算列读取相同默认值。保存预算递增 revision，旧准确预览拒绝发送；原消息和原报告不重写。
- 规划器优先最近完整消息，对较旧消息作确定性的本机逐字摘录。额度按 UTF-16 字符计，摘录不会截断代理对。摘要标明“历史用户说法 / 既往助手回答，未经验证”，不会把“用户确认”语义提升为已验证事实，不产生新的模型调用或记忆。全部原始消息仍可阅读。
- 总预算计算最终序列化系统/用户 JSON 正文，包含指令、嵌套 JSON 转义、引用快照、摘要和省略说明。先减少可选摘录，再减少较早的完整历史。原报告、当前问题和本次新选分段始终完整；必要输入仍超预算时明确拒绝，要求提高预算或减少新选分段。64000 是绝对字符上限，不保证任一供应商的 token 上下文窗口。页面显示字符量，实际 token 用量只采用供应商明确返回值。
- B0 预览有效 10 分钟，全局最多 100 个。模型异步执行最多 2 个，等待队列最多 8 个；尝试自受理起 30 秒超时，取消/超时后禁止迟到发布。网络请求使用现有模型客户端的响应体上限、单次尝试和无重定向设置，不增加传输重试。
- `REFERENCE_ONLY` 为未调用模型；其他状态有 `RUNNING`、`SUCCEEDED`、`FAILED`、`INVALID_OUTPUT`、`CANCELLED`、`TIMED_OUT`、`INTERRUPTED`、`BUSY`。仅保存服务明确返回的有效 token 用量；失败或取消时未知用量不按零计费。
- HTTP 400：输入/授权不完整；404：报告、对话或准确分段不存在；409：范围、版本、模型或 revision 已变化/已有活动尝试；410：预览已消费/过期/重启失效；429：对话、消息、预览或队列达到上限；503：模型不可用。页面保留原材料，按提示刷新或重新预览。

`summary` 包含稳定 SHA256 `version`、来源标签 `provenance`、`excerpts`、总条数/近期完整条数/摘录条数/完全省略条数、`clippedCharacters`、`recentOrdinals` 和 `omittedOrdinals`。消息序号从 1 开始，摘录保留 `messageId`、role、attribution、`[startOffset,endOffset)`、原始字符数、逐字 text、不可变引用快照和 documentsDerived。版本由实际消息 ID、摘录/范围及预算确定，不用当前时间或随机数。未纳入字符包含完全省略消息及摘录未覆盖部分；这些文字未提供给模型。

详情 GET 的 summary 是按保存预算生成的本机历史视图；填写问题并调用 `/context` 或 `/previews` 后，summary 才反映本次完整正文实际容纳的范围。Context 另有 `budget`、`serializedCharacters`、`text`、`fingerprint`；serializedCharacters 等于最终 text 的 UTF-16 长度。准确正文的 summary 用 evidenceIds 指向去重的顶层 citation 快照；Detail/Context 响应保留完整历史引用供本机查看。保存的引用在来源被删除后仍可打开；本机审阅旧快照不要求模型/原来源仍有效，新的模型发送仍校验所有实际纳入来源的有效范围/版本。摘要中任何资料派生内容也要求独立 `documentsConfirmed`，本次无新增选段时仍然如此。被省略消息的资料不会暗中加入正文。

追问本身不自动生成跨任务记忆、向量召回、工具执行或新分析器。M1 可在单条已保存消息上显式点击“保存为记忆候选”，逐字复制为未确认候选并保留出处；只有有原始 PROJECT/REPOSITORY UUID 的对话可用，旧服务身份不会猜测绑定。M2 支持按当前问题召回同范围已确认记忆并显式勾选最多 3 条随本次问题发送：召回只匹配 CONFIRMED 当前版本，同主题冲突与适用性变化同时展示；发送须在通用与资料确认之外单独勾选记忆外发，回答通过 `memory:{id}:{revision}` 引用实际使用的记忆。记忆不会未经勾选自动进入模型上下文。详见[项目记忆](PROJECT_MEMORY.md)。

## 无 API Key 的本地查看步骤

1. 使用 JDK 21，在仓库执行 `./mvnw package`（Windows 为 `./mvnw.cmd package`），然后运行 `java -jar target/agent-triage-0.12.0.jar --triage.mode=DEMO`。默认打开 `http://127.0.0.1:18080/`，无需配置模型密钥或 SQL 数据。
2. 在“模型设置”确认当前为“规则演示”模式（已保存的 MODEL 选择可能优先于启动参数），再在“服务排查”填写一个问题，例如“哪些证据支持当前结论？”并执行排查，等待演示报告完成。在报告上点击“继续追问”，再点“从报告开始追问”。也可从“历史与统计 → 历史记录”的报告进入。
3. 在“任务追问”将“优先保留最近完整消息”改为 2、“较旧逐字摘录字符”保留 3000，点“保存预算”。依次填写并点击“保存问题并查看原依据”至少 4 次，例如“用户说已确认连接数，但仍需检查超时”“还缺哪些证据”“接下来检查什么”“如何核对版本”。界面会显示每次“未调用模型”，不生成助手消息。
4. 再填一个尚未保存的问题，点“本机查看本次上下文（无需模型）”。展开“本机历史摘录、版本、覆盖范围与引用”，核对近期完整消息、带引号性质的旧用户说法、稳定版本、完全省略序号、未纳入字符，以及下方实际完整序列化大小和正文。
5. 在摘录中点“查看消息原文 #…”核对逐字摘录与保存的完整消息；若历史已有模型资料引用，还可点“查看摘要引用”查看当时的路径、版本、行/偏移和哈希。原报告在“创建时的原报告快照”中独立可读。
6. 将摘要字符改为 0 并保存，再次本机查看，旧摘录消失且省略数增加。刷新页面或点击同一已保存追问，预算、完整消息、原报告及历史摘录恢复。修改问题/资料/预算会清除先前准确预览和确认；若日后启用模型，仍需重新生成模型发送预览并独立确认资料外发。

## 验证

JDK 21：`./mvnw verify`。针对性回归：`./mvnw -Dtest=ConversationApiTest,ConversationStoreTest,ConversationModelResponderTest,MaterialApiTest,SendPreviewServiceTest test`。页面：`node scripts/conversation_ui_check.js`（含无密钥上下文、预算保存、陈旧响应与文本安全） 及现有四个研发报告、知识检索、材料页面检查。

模型测试使用本机 HTTP OpenAI 桩，不调用实际供应商。`ConversationApiTest` 可通过 `TRIAGE_CONVERSATION_TEST_DB_URL`、`TRIAGE_CONVERSATION_TEST_DB_USER`、`TRIAGE_CONVERSATION_TEST_DB_PASSWORD` 指向独立 PostgreSQL 测试库；CI 已接入现有 PostgreSQL 作业。V13/V14 SQL 同时支持 H2/PostgreSQL，不对原报告/登记建立级联删除关系。
