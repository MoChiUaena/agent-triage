# 模型配置

默认使用 `DEMO` 模式，按固定规则运行。`MODEL` 模式通过 Spring AI 调用 DeepSeek，由模型选择工具并生成结论。两种模式都查询仓库提供的合成数据。

模型链路已通过本地模拟服务测试。真实 DeepSeek 调用仍待配置凭据后验证，当前没有模型效果评测结果。

## 启动模型模式

先按 README 配置 JDK 21，并停止占用 18080 端口的旧服务。在 PowerShell 中设置：

```powershell
$env:TRIAGE_MODE = 'MODEL'
$env:TRIAGE_MODEL_BASE_URL = 'https://api.deepseek.com'
$env:TRIAGE_MODEL_NAME = 'deepseek-flash'
$env:TRIAGE_MODEL_API_KEY = [System.Net.NetworkCredential]::new('', (Read-Host 'DeepSeek API Key' -AsSecureString)).Password
.\mvnw.cmd spring-boot:run
```

密钥输入不会显示在终端，也不需要写入文件。项目不会自动加载 `.env`。

打开 <http://127.0.0.1:18080>，右上角应显示“模型模式”。`GET /api/config` 返回当前模式和模型名称，不返回密钥或服务地址。缺少密钥时，模型模式启动失败，不会退回演示模式。

恢复演示模式：停止服务，把 `TRIAGE_MODE` 改为 `DEMO` 后重新启动。已有记录保留各自的运行模式。

## 参数

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `TRIAGE_MODE` | `DEMO` | `DEMO` 或 `MODEL` |
| `TRIAGE_MODEL_BASE_URL` | `https://api.deepseek.com` | 服务根地址，应用追加 `/chat/completions` |
| `TRIAGE_MODEL_NAME` | `deepseek-flash` | 请求中的模型名称 |
| `TRIAGE_MODEL_API_KEY` | 空 | MODEL 模式必填 |
| `TRIAGE_MODEL_TIMEOUT` | `20s` | 单次模型等待时限，最多 60s |
| `TRIAGE_MODEL_MAX_ROUNDS` | `4` | 每次排查最多调用模型的轮数，范围 1–8 |
| `TRIAGE_MODEL_MAX_TOKENS` | `1600` | 单次模型输出 token 上限，范围 256–4096 |
| `TRIAGE_RUN_TIMEOUT` | `60s` | 整体执行时限，最多 120s |
| `TRIAGE_MAX_TOOL_CALLS` | `3` | 每次排查的工具调用上限 |

也可用启动参数 `--triage.model.max-rounds=4`、`--triage.model.max-tokens=1600` 设置。

当前接入非思考模式，发送 `thinking.type=disabled`，temperature 为 0。不启用服务端 beta strict 模式，参数和输出由应用校验。实现使用 Chat Completions 兼容协议，但目前只提供 DeepSeek 配置；其他服务需要另行验证其参数支持情况。

服务地址必须使用 HTTPS，本机协议测试可使用回环 HTTP 地址。地址不能携带用户名、密码或查询参数；HTTP 重定向不会被自动跟随。

## 工具调用与结果

模型只能请求文档、指标和日志三个工具，服务和时间窗口必须与本次请求一致。同一工具的重复参数会终止执行。工具错误、模型错误和超时都会保存为 FAILED，保留已收集的证据。

最终结果必须包含观察、可能原因、检查建议和不确定性。成功结论需要引用本次文档、指标和日志；仅引用排障文档不能证明服务当前状态。格式错误、无效引用或输出截断不会保存为成功结果。

应用不自动重试模型请求。浏览器断线重连只恢复进度，不会重新提交排查任务。

## 费用与记录

模型调用费用来自配置的服务商，以 [DeepSeek 控制台](https://platform.deepseek.com/)账单为准。一次排查可能多轮调用模型，默认最多 4 轮。

执行记录保存配置的模型名、服务端返回的模型名、调用轮数，以及服务端提供的 token 用量。如果任一轮没有完整用量，合计显示为空，不估算费用或 token。模型出错或超时也可能已经产生费用。

用户问题和工具返回的合成证据会发送给模型服务。应用保存问题、工具事件、证据和最终结论，不保存原始模型对话。不要在问题中输入凭据或业务敏感数据。

## 验证

离线集成测试使用本机 HTTP 模拟服务，不访问 DeepSeek：

```powershell
.\mvnw.cmd '-Dtest=ModelIntegrationTest,ModelSettingsTest' test
```

配置好真实模型并启动应用后，可执行两种场景的冒烟检查：

```powershell
python scripts/model_smoke.py --allow-model-calls
```

脚本将结果写入 `target/model-smoke/`，失败的执行也会保留。它只检查调用链路，不替代后续的独立评测。

接口依据：[DeepSeek 工具调用](https://api-docs.deepseek.com/guides/tool_calls/)、[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、[Spring AI 工具执行](https://github.com/spring-projects/spring-ai/blob/v1.1.8/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/tools.adoc)。
