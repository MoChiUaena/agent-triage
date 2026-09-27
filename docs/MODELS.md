# 模型配置

默认使用 `DEMO` 模式，按固定规则运行。`MODEL` 模式通过 Spring AI 调用页面中选择的模型服务，由模型选择工具并生成结论。观测数据源单独配置：默认查询合成数据；设置 `TRIAGE_OBSERVATION_SOURCE=LIVE` 后查询本地订单样例服务实际处理的请求。

模型链路已通过本地模拟服务测试。真实 DeepSeek 调用仍待配置凭据后验证，当前没有模型效果评测结果。

## 在页面中配置

按 README 启动应用，打开 <http://127.0.0.1:18080/settings.html>：

1. 点击“添加服务”，选择预设服务，确认 Base URL 和模型名称，填写 API Key。LM Studio 的模型 ID 需按本机实际加载的模型填写。
2. 保存后点击“测试”。测试会发起一次短模型请求，可能产生服务商费用，不会自动启用该配置。它验证聊天接口是否返回文本，不能替代工具调用和排查结果检查。
3. 点击“设为当前模型”，返回排查页面开始新任务。也可以在设置页切回演示模式。

编辑服务时，API Key 输入框保持空白；留空保存会保留现有 Key。更改 Base URL 时必须重新填写 Key。列表只显示 Key 是否已保存，接口不返回完整 Key 或部分字符。

配置保存在当前数据库，Key 使用 AES-GCM 加密；本地加密密钥文件默认为 `data/model-config.key`。数据库和这个文件需要一起备份。密钥文件丢失后，已保存的 Key 无法解密；可以恢复备份，或切回演示模式并删除旧服务，再重新添加。

页面保存和切换会立即影响**新任务**。已提交的任务继续使用提交时的配置，并在执行记录中保存模型服务名称和配置版本。页面最多保存 10 个服务；首版只使用聊天模型，无需配置向量或语音服务。

| 页面预设 | Base URL | 默认模型 | 请求参数 |
|---|---|---|---|
| DeepSeek | `https://api.deepseek.com` | `deepseek-flash` | 非思考模式 |
| 阿里云百炼 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` | 兼容接口 |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-5.3` | 温度 1 |
| Kimi | `https://api.moonshot.cn/v1` | `kimi-k2.6` | 非思考模式，温度固定 0.6 |
| LM Studio | `http://localhost:1234/v1` | 手动填写 | 本机兼容接口，默认 Key 为 `local` |
| 其他兼容接口 | 手动填写 | 手动填写 | 通用 Chat Completions 参数 |

预设字段可以编辑。Kimi 的非思考参数只对 `kimi-k2.6` 自动启用；更换其他型号时，请根据该型号文档调整温度并测试连接。

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

DeepSeek 和 Kimi K2.6 预设发送 `thinking.type=disabled`。百炼、GLM、LM Studio 和通用兼容接口不发送此参数。温度可在页面设置，环境变量模式默认为 0。不启用服务端 beta strict 模式，参数和输出由应用校验。各预设走 Chat Completions 接口，真实模型调用仍需用户凭据验证。

服务地址必须使用 HTTPS，本机服务可使用回环 HTTP 地址。地址不能携带用户名、密码或查询参数；HTTP 重定向不会被自动跟随。

## 工具调用与结果

模型只能请求文档、指标和日志三个工具，服务和时间窗口必须与本次请求一致。同一工具的重复参数会终止执行。工具错误、模型错误和超时都会保存为 FAILED，保留已收集的证据。

最终结果必须包含观察、可能原因、检查建议和不确定性。成功结论需要引用本次文档、指标和日志；仅引用排障文档不能证明服务当前状态。格式错误、无效引用或输出截断不会保存为成功结果。

应用不自动重试模型请求。浏览器断线重连只恢复进度，不会重新提交排查任务。

## 费用与记录

模型调用费用来自配置的服务商，以 [DeepSeek 控制台](https://platform.deepseek.com/)账单为准。一次排查可能多轮调用模型，默认最多 4 轮。

执行记录保存选用的服务、配置版本、模型名、调用轮数，以及服务端提供的 token 用量。如果任一轮没有完整用量，合计显示为空，不估算费用或 token。模型出错或超时也可能已经产生费用。

用户问题和工具返回的证据会发送给模型服务；LIVE 数据源包含本地样例请求的耗时与错误事件。应用保存问题、工具事件、证据和最终结论，不保存原始模型对话。不要在问题中输入凭据或业务敏感数据。

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

接口依据：[DeepSeek 工具调用](https://api-docs.deepseek.com/guides/tool_calls/)、[百炼兼容地址](https://github.com/alibaba/open-code-review/blob/486022daaf14f7142275eddb9b3cacc3cc5dadfa/pages/src/content/docs/zh/configuration.md)、[百炼模型示例](https://github.com/alibaba/spring-ai-alibaba/blob/f82da0b50f35744c13968191be2b1cd2452ef550/examples/documentation/src/main/resources/application.yml)、[GLM API 指引](https://docs.bigmodel.cn/cn/api/introduction)、[Kimi K2.6](https://platform.kimi.com/docs/guide/kimi-k2-6-quickstart)、[Spring AI 工具执行](https://github.com/spring-projects/spring-ai/blob/v1.1.8/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/tools.adoc)。页面的服务管理交互参考了 [interview-guide](https://github.com/Snailclimb/interview-guide/tree/969e2af8550f680688f5b50f0d895186a661a319)。
