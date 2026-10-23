# 已保存报告的连续追问（C1）

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
| `GET /api/conversations/{id}` | 不可变 `original`、`revision`、`activeAttempt`、`messages`、`attempts`、当前 `model` 与边界说明 |
| `POST /api/conversations/{id}/references` | `{ "revision": 0, "question": "依据是什么？" }`，200 保存用户问题和 `REFERENCE_ONLY` 尝试，不产生助手消息 |
| `POST /api/conversations/{id}/previews` | `{ "revision": 0, "question": "下一步？", "selectedChunks": [{ "versionId": "UUID", "chunkId": "UUID" }] }`，201 返回 `{context, preview}` |
| `POST /api/conversations/{id}/attempts` | `{ "previewId": "UUID", "confirmed": true, "documentsConfirmed": true }`，202 返回已受理状态；发送仅使用该预览的问题和正文，不接收替换问题 |
| `POST /api/conversations/{id}/attempts/{attemptId}/cancel` | `{}`，200 返回取消后的详情；取消不代表外部服务未计费 |

`preview` 复用 B0 的接收方、不可变材料、完整正文、SHA256、时间和省略字段。正文包含真正发送的系统/用户消息及固定输出限制。`context` 显示纳入的历史和引用清单，`documentsRequired` 明确资料派生历史是否需要额外授权。预览绑定对话 UUID、revision、准确问题、历史、引用、接收方和配置版本，只能消费一次。原报告/源码版本、当前资料版本/来源有效性或模型改变后必须重新预览。历史知识引用可以独立阅读，但不允许把失效资料用于新的发送。

用户消息带有 attemptId；助手消息仅在该尝试仍为当前活动尝试时原子发布。引用 ID 为 `report:<report UUID>` 或 `chunk:<chunk UUID>`，引用对象保存原报告或 K2 准确分段的不可变快照。未知引用、重复/额外 JSON 字段、工具调用、截断和非完整 JSON 输出都会被拒绝。模型只可返回 `{answer,evidenceIds}`。

## 固定边界与状态

- 最多保存 1000 个对话，每对话最多 80 条消息。模型准入预留一条助手消息空间。同一对话只允许一个活动尝试，乐观 revision 防止双击和并发消息交叉。
- 问题 1—4000 字符；模型回答最多 8000 字符，输出 tokens 上限为 2048 与当前模型配置上限中的较小值；最多 3 个本次选段。精确上下文最多 64000 字符。
- 历史只纳入最近最多 12 条、合计最多 24000 字符的消息。达到范围时在预览明确列出省略条数，全部原消息仍保存在本机；不生成摘要，不静默改写旧回答。达到正文上限时拒绝发送，并要求减少材料或新建对话。
- B0 预览有效 10 分钟，全局最多 100 个。模型异步执行最多 2 个，等待队列最多 8 个；尝试自受理起 30 秒超时，取消/超时后禁止迟到发布。网络请求使用现有模型客户端的响应体上限、单次尝试和无重定向设置，不增加传输重试。
- `REFERENCE_ONLY` 为未调用模型；其他状态有 `RUNNING`、`SUCCEEDED`、`FAILED`、`INVALID_OUTPUT`、`CANCELLED`、`TIMED_OUT`、`INTERRUPTED`、`BUSY`。仅保存服务明确返回的有效 token 用量；失败或取消时未知用量不按零计费。
- HTTP 400：输入/授权不完整；404：报告、对话或准确分段不存在；409：范围、版本、模型或 revision 已变化/已有活动尝试；410：预览已消费/过期/重启失效；429：对话、消息、预览或队列达到上限；503：模型不可用。页面保留原材料，按提示刷新或重新预览。

本批不包含 C2 摘要/自适应预算、跨任务记忆、向量召回、工具执行或新分析器。

## 验证

JDK 21：`./mvnw verify`。针对性回归：`./mvnw -Dtest=ConversationApiTest,ConversationStoreTest,ConversationModelResponderTest,MaterialApiTest,SendPreviewServiceTest test`。页面：`node scripts/conversation_ui_check.js` 及现有四个研发报告、知识检索、材料页面检查。

模型测试使用本机 HTTP OpenAI 桩，不调用实际供应商。`ConversationApiTest` 可通过 `TRIAGE_CONVERSATION_TEST_DB_URL`、`TRIAGE_CONVERSATION_TEST_DB_USER`、`TRIAGE_CONVERSATION_TEST_DB_PASSWORD` 指向独立 PostgreSQL 测试库；CI 已接入现有 PostgreSQL 作业。V13 SQL 同时支持 H2/PostgreSQL，不对原报告/登记建立级联删除关系。
