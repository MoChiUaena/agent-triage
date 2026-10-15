# 保存报告的共享材料与发送预览（B0）

历史与统计页的「材料 / 发送预览」可阅读已保存的服务排查、PR、Issue、CI 和协同工作流报告。历史表及研发报告中的「材料 / 发送预览」链接会带入报告 ID 和登记范围；也可手动选择范围并填写报告 UUID。页面展示接收方、字段范围、快照 SHA256、读取时间、缺失 / 脱敏 / 裁剪说明及完整预览正文，并提供原报告链接。

本阶段不会调用模型，不需要模型密钥。没有配置模型时仍可阅读材料。这里的「可用」只表示模型配置可解析，不代表已经探测远程服务。

## 无密钥复现

先用 JDK 21 和独立内存数据库启动本机演示模式（默认端口 18080）：

```powershell
$env:JAVA_HOME='C:/Program Files/Java/latest/jdk-21'
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--triage.mode=DEMO --spring.datasource.url=jdbc:h2:mem:material-demo;DB_CLOSE_DELAY=-1'
```

另开 PowerShell 创建一个固定规则报告，等待终态，再生成材料和预览：

```powershell
$base='http://localhost:18080'
$run=Invoke-RestMethod "$base/api/runs" -Method Post -ContentType 'application/json' -Body (@{
  question='为什么订单接口超时？'; service='order-service'; windowMinutes=5; scenario='DOWNSTREAM_TIMEOUT'
} | ConvertTo-Json)
do {
  Start-Sleep -Milliseconds 200
  $run=Invoke-RestMethod "$base/api/runs/$($run.id)"
} while ($run.status -in @('QUEUED','RUNNING'))
$headers=@{'X-Triage-Material'='1'}
$selection=@{type='SERVICE';reportId=$run.id;service=$run.service}
$material=Invoke-RestMethod "$base/api/materials/resolve" -Method Post -Headers $headers -ContentType 'application/json' -Body ($selection | ConvertTo-Json)
$preview=Invoke-RestMethod "$base/api/materials/previews" -Method Post -Headers $headers -ContentType 'application/json' -Body (@{references=@($selection)} | ConvertTo-Json -Depth 6)
$material.reference
$preview.recipient
$preview.omissions
$preview.text
# 在浏览器打开 $base/workspace.html?type=SERVICE&report=<报告ID>&service=order-service#materials
```

`GET /api/materials/previews/{id}` 可在有效期内重新读取同一预览。无模型时对 `/api/materials/previews/{id}/consume` POST `{"confirmed":true}` 返回 503，不会发送。无需为本步骤添加任何密钥。PR / Issue / CI / WORKFLOW 可引用本机已经保存的报告，选择 `repositoryId` 和对应类型；该流程不重新访问 GitHub。

## REST 与 Java 接口

所有材料 API 仅允许本机同源访问；写请求须带 `X-Triage-Material: 1`，响应为 `Cache-Control: no-store`。

| API | 行为 |
| --- | --- |
| `GET /api/materials/scopes` | 返回登记项目、仓库与历史服务的安全选择项，不包含源码路径或凭据 |
| `POST /api/materials/resolve` | 输入 `Selection`，仅从保存报告解析 `Material` |
| `POST /api/materials/previews` | 输入 `{"references":[Selection,...]}`，返回 201 和完整 `Preview` |
| `GET /api/materials/previews/{id}` | 读取未过期预览 |
| `POST /api/materials/previews/{id}/consume` | 输入 `{"confirmed":true}`，重新验证并一次性返回 `Consumed`，不调用模型 |

公开契约位于 `material/MaterialModels.java`：

- `Selection(Type type, UUID reportId, UUID projectId, UUID repositoryId, String service)`：调用方只选择保存的 ID，内容和范围由服务器核对。类型为 `SERVICE/PR/ISSUE/CI/WORKFLOW/KNOWLEDGE`。知识使用不可变原文版本 UUID，并必须明确提供已登记项目或仓库范围；详见 [项目知识](PROJECT_KNOWLEDGE.md)。
- `MaterialService.scopes(): List<ScopeOption>`；`MaterialService.resolve(Selection): Material`。
- `SendPreviewService.create(List<Selection>): Preview`；`get(UUID): Preview`；`consume(UUID, boolean): Consumed`。
- `Reference` 保存来源类型、报告 ID、服务器推导的 `Scope`、版本、保存快照内容 SHA256、读取时间、确认状态和本机原报告链接。`Material` 保存实际正文、准确字段路径和省略说明。
- `Preview` 保存当前接收方身份 / 配置版本 / Base URL、材料引用、完整正文及正文 SHA256、裁剪说明、创建和过期时间。`Consumed` 返回同一正文和哈希、接收方及 `CONFIRMED` 引用。

后续 C1/A1/A2 调用方必须在真正发送前消费预览，并仅使用返回的正文；不能重新拼接最新报告或直接使用读取预览 API 代替消费验证。接收方检查不会发送探测请求，也不会返回模型客户端或密钥。B0 不接受任意聊天正文或用户提供的材料内容，未来的问题 / 历史拼接需在创建预览时单独扩展并完整预览。

K1 知识适配：`consume(UUID,boolean,boolean documentsConfirmed)` / REST `documentsConfirmed` 另行确认资料外发，默认 false。普通 `confirmed`、本机导入或本机预览均不授予文档发送权限。存在 KNOWLEDGE 时，旧双参数消费方法拒绝；当前知识来源必须仍有效且范围仍登记。历史原文读取不要求登记仍存在。

## 范围与失效规则

服务报告保留当时保存的 `sourceAnalysis.projectId`，并核验当前登记及服务绑定；不会根据服务名任选一个当前项目。未保存项目身份的旧报告保持服务范围，拒绝调用方补填项目身份。仓库报告按保存的 `repositoryId` 校验登记。传入的范围只能约束解析结果，不能修改它；项目和仓库输入不得混用。单个预览内所有引用的完整范围必须相同。

版本包含保存快照 SHA256；有源码项目身份时还绑定当前索引版本和索引哈希。消费时重新解析材料并核对版本、范围、哈希和正文，同时核对当前模型选择、配置版本、模型名及 Base URL。过期、已经使用、重启、材料更改 / 删除、项目移除 / 解绑、索引更新、模型切换或配置更改都会要求重新预览。原报告不会被修改。

## 边界

- 每份材料正文最多 16,000 字符，单字段最多 2,000 字符，每个数组最多 20 项；裁剪和缺失记录具体字段路径。一次最多 4 份不重复的报告，进程最多保留 100 个预览，10 分钟过期。达到上限返回 429，过期后释放容量。预览不持久化，历史消费者今后自行保存引用。
- 共享内容使用明确字段白名单，二次复用现有凭据脱敏规则。任意证据 `data`、源码 `excerpt/content/body/patch`、完整日志、执行事件及源码分析对象不发送；Issue 证据文本可能包含源码，统一省略。CI 已保存的有限脱敏日志片段可以进入正文，不存在原始日志旁路。确认预览不能授权源码分享。
- 工作流只包含计划、任务状态、仓库概览、检查项和摘要。内嵌 PR / Issue / CI 子报告正文不重复加入，需要分别引用已保存子报告。
- 不读取当前磁盘源码、不验证远程仓库最新提交、不刷新运行观测。源码文件改变而尚未重新索引时，不会发现磁盘变化；这里不发送任何源码片段。更改或删除旧材料不会改变已保存原报告。
- 材料明确标为不可信证据，不能作为系统指令或执行命令。脱敏属于尽力过滤，不能把任意文本当作安全脚本；后续模型调用方仍须使用独立系统约束。
- 页面目前一次选择一份材料；Java / REST 支持同范围最多四份。K1 已扩展知识不可变版本适配；持续追问、模型调用和外部写入不在此接口内实现。

## 离线验证

```powershell
.\mvnw.cmd '-Dtest=MaterialApiTest,SendPreviewServiceTest' test
node scripts/material_ui_check.js
node scripts/development_ui_check.js
.\mvnw.cmd verify
```

测试使用 H2、保存快照及不发请求的本机模型配置；覆盖五类来源、跨范围拒绝、旧报告不变、脱敏裁剪、源码身份与索引变化、精确正文消费、过期、模型和材料变化、预览容量，以及 UI 文本转义与异步过期结果清理。
