# 模型配置

默认使用 `DEMO` 模式，按固定规则运行。`MODEL` 模式通过 Spring AI 调用页面中选择的模型服务，由模型选择工具并生成结论。两种模式都查询仓库提供的合成数据。

模型链路已通过本地模拟服务测试。真实 DeepSeek 调用仍待配置凭据后验证，当前没有模型效果评测结果。

## 在页面中配置

按 README 启动应用，打开 <http://127.0.0.1:18080/settings.html>：

1. 点击“添加服务”，选择 DeepSeek 或 OpenAI 兼容接口，填写 Base URL、模型名称和 API Key。
2. 保存后点击“测试”。测试会发起一次短模型请求，可能产生服务商费用，不会自动启用该配置。
3. 点击“设为当前模型”，返回排查页面开始新任务。也可以在设置页切回演示模式。

编辑服务时，API Key 输入框保持空白；留空保存会保留现有 Key。更改 Base URL 时必须重新填写 Key。列表只显示 Key 是否已保存，接口不返回完整 Key 或部分字符。

配置保存在当前数据库，Key 使用 AES-GCM 加密；本地加密密钥文件默认为 `data/model-config.key`。数据库和这个文件需要一起备份。密钥文件丢失后，已保存的 Key 无法解密；可以恢复备份，或切回演示模式并删除旧服务，再重新添加。

页面保存和切换会立即影响**新任务**。已提交的任务继续使用提交时的配置，并在执行记录中保存模型服务名称和配置版本。页面最多保存 10 个服务；首版只使用聊天模型，无需配置向量或语音服务。

设置接口只允许本机访问。外部浏览器或跨站请求无法修改配置。当前应用没有多用户登录功能，不应将此设置页作为公共管理后台开放。

## 通过环境变量配置

已有环境变量配置可继续使用。先按 README 配置 JDK 21，并停止占用 18080 端口的旧服务。在 PowerShell 中设置：

```powershell
$env:TRIAGE_MODE = 'MODEL'
$env:TRIAGE_MODEL_BASE_URL = 'https://api.deepseek.com'
$env:TRIAGE_MODEL_NAME = 'deepseek-flash'
$env:TRIAGE_MODEL_API_KEY = [System.Net.NetworkCredential]::new('', (Read-Host 'DeepSeek API Key' -AsSecureString)).Password
.\mvnw.cmd spring-boot:run
```

密钥输入不会显示在终端，也不需要写入文件。项目不会自动加载 `.env`。

打开 <http://127.0.0.1:18080>，右上角应显示“模型模式”。`GET /api/config` 返回当前模式和模型名称，不返回密钥或服务地址。没有页面选择记录时，应用使用环境变量的运行模式。页面中选择模型或演示模式后，保存的选择优先生效。

环境变量缺少模型凭据时，页面仍可打开以添加服务；在配置完成前，模型模式不能执行排查。已有记录保留各自的运行模式。

## 参数

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `TRIAGE_MODE` | `DEMO` | 尚未在页面选择模式时的默认值：`DEMO` 或 `MODEL` |
| `TRIAGE_MODEL_BASE_URL` | `https://api.deepseek.com` | 服务根地址，应用追加 `/chat/completions` |
| `TRIAGE_MODEL_NAME` | `deepseek-flash` | 请求中的模型名称 |
| `TRIAGE_MODEL_API_KEY` | 空 | MODEL 模式必填 |
| `TRIAGE_MODEL_TIMEOUT` | `20s` | 单次模型等待时限，最多 60s |
| `TRIAGE_MODEL_MAX_ROUNDS` | `4` | 每次排查最多调用模型的轮数，范围 1–8 |
| `TRIAGE_MODEL_MAX_TOKENS` | `1600` | 单次模型输出 token 上限，范围 256–4096 |
| `TRIAGE_RUN_TIMEOUT` | `60s` | 整体执行时限，最多 120s |
| `TRIAGE_MAX_TOOL_CALLS` | `3` | 每次排查的工具调用上限 |
| `TRIAGE_SETTINGS_KEY_FILE` | `./data/model-config.key` | 页面配置的本地加密密钥文件 |

也可用启动参数 `--triage.model.max-rounds=4`、`--triage.model.max-tokens=1600` 设置。

DeepSeek 类型使用非思考模式，发送 `thinking.type=disabled`。通用兼容接口不发送此参数。温度可在页面设置，环境变量模式默认为 0。不启用服务端 beta strict 模式，参数和输出由应用校验。通用兼容接口采用 Chat Completions 协议，具体模型仍需自行测试。

服务地址必须使用 HTTPS，本机服务可使用回环 HTTP 地址。地址不能携带用户名、密码或查询参数；HTTP 重定向不会被自动跟随。

## 工具调用与结果

模型只能请求文档、指标和日志三个工具，服务和时间窗口必须与本次请求一致。同一工具的重复参数会终止执行。工具错误、模型错误和超时都会保存为 FAILED，保留已收集的证据。

最终结果必须包含观察、可能原因、检查建议和不确定性。成功结论需要引用本次文档、指标和日志；仅引用排障文档不能证明服务当前状态。格式错误、无效引用或输出截断不会保存为成功结果。

应用不自动重试模型请求。浏览器断线重连只恢复进度，不会重新提交排查任务。

## 费用与记录

模型调用费用来自配置的服务商，以 [DeepSeek 控制台](https://platform.deepseek.com/)账单为准。一次排查可能多轮调用模型，默认最多 4 轮。

执行记录保存选用的服务、配置版本、模型名、调用轮数，以及服务端提供的 token 用量。如果任一轮没有完整用量，合计显示为空，不估算费用或 token。模型出错或超时也可能已经产生费用。

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

接口依据：[DeepSeek 工具调用](https://api-docs.deepseek.com/guides/tool_calls/)、[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、[Spring AI 工具执行](https://github.com/spring-projects/spring-ai/blob/v1.1.8/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/tools.adoc)。页面的服务管理交互参考了 [interview-guide](https://github.com/Snailclimb/interview-guide/tree/969e2af8550f680688f5b50f0d895186a661a319)。
