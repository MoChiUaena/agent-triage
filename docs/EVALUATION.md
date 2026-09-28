# 小规模评测

## v3：参数纠正与检查建议

参数反馈允许一次有界更正，检查建议按已有证据限制。[v3 调试与回归记录](validation/2026-09-28-v3-dev.md)为 3 条满足契约、1 条应用门槛，真实模型没有触发参数纠正；该路径另外用本地接口模拟器注入无效参数验证。

[v3 案例](evaluation/cases-v3.json)含 4 个调试问题和 8 个新的留出问题，使用[标准 v3](evaluation/rubric-v3.md)。P01/P02 复用的 v2 问题只作回归。运行方法：

```powershell
python scripts/compare_live_methods.py --allow-model-calls --dataset docs/evaluation/cases-v3.json
python scripts/compare_live_methods.py --allow-model-calls --dataset docs/evaluation/cases-v3.json --split holdout --allow-holdout
```

这些命令调用已启用的真实模型，可能产生费用；原始记录保存在忽略目录，公开报告只保留脱敏结果。

## v2：证据约束输出

v1 自由文本报告作为历史保留。2026-09-28 改为[模型判断契约](MODEL_OUTPUT.md)：模型选工具、判断类型、证据和检查项，应用生成窗口事实及关键措辞。需要评价系统是否守住证据边界，不能把更稳定的应用措辞称为模型自由归因能力提升。

新建 [v2 案例](evaluation/cases-v2.json)有 6 个调试例、10 个留出例，包含混合请求与空窗口，并用[标准 v2](evaluation/rubric-v2.md)检查判断、事实与生成来源。脚本支持显式流量计划，两种方法回答前后会核对请求数与场景是否发生变化：

```powershell
python scripts/compare_live_methods.py --allow-model-calls --dataset docs/evaluation/cases-v2.json
# 调试完成、标准固定后，首次运行新的留出集
python scripts/compare_live_methods.py --allow-model-calls --dataset docs/evaluation/cases-v2.json --split holdout --allow-holdout
```

两条命令可能向已启用模型服务发起多轮计费请求。原始结果保存在被忽略的 `target/`，报告不包含 API Key、提供商配置 ID 和 traceId 明文。旧执行历史仍是旧版文本，页面会区分来源。

[v2 调试记录](validation/2026-09-28-dev-contract.md)保留了最初三次校验失败、漏选规则的复查和修订后的 6 例结果：3 条满足契约，3 条由应用门槛结束。

[v2 首次留出对照](validation/2026-09-28-holdout-contract.md)已完成：5 条满足契约、4 条应用门槛、1 条工具参数失败。正常案例的检查建议仍有欠针对性的情况。该留出集已使用，后续修改只能用它作回归，不能继续作为独立效果证明。

## v1：历史记录

案例集 [`cases-v1.json`](evaluation/cases-v1.json) 有 20 个问题，`dev` 和 `holdout` 各 10 个，两组已完成真实模型对照。固定规则的演示模式使用合成数据，真实模型对照使用 LIVE 请求；`expectedDemoStatus` 只用于演示模式的状态检查。

v1 留出集已经使用，后续修改不能再用它证明独立效果。公开的案例量很小，不能用来声称通用故障诊断准确率。

## 运行演示模式检查

建议单独启动实例，避免在日常演示的历史记录中加入评测数据。先构建 JAR，再在一个终端运行：

```powershell
java -jar target/agent-triage-0.1.0-SNAPSHOT.jar --server.port=18081 '--spring.datasource.url=jdbc:h2:mem:evaluation;DB_CLOSE_DELAY=-1' --triage.settings.key-file=target/evaluation-key --triage.mode=DEMO
```

另一个终端执行：

```powershell
python scripts/evaluate_demo.py --split dev
```

脚本先确认服务处于合成数据的 DEMO 模式，再逐个提交案例。每条结果记录预期与实际状态、工具次数、证据数、引用 ID 是否属于本次执行、事件顺序和客户端等待时间。完整执行记录与摘要保存在 `target/evaluation/`；该目录不提交。需要首次检查留出集时，显式使用 `--split holdout`。

[首次调试集结果](validation/2026-09-27-eval-dev.json)：10/10 通过上述状态和结构检查。它只说明固定规则在这 10 个输入上的行为符合预期，没有验证模型选工具或诊断文字的语义正确性。工具抛错与超时由 `RunServiceTest` 注入故障检查，目前没有公开 HTTP 端点用于制造工具失败。

## 后续比较

仅检索文档的对照方法已经固定，每例记录工具选择、引用、结果状态、耗时和失败样例。真实模型评测记录具体模型与参数；模型返回完整 usage 时才统计 token。普通 CI 不调用付费模型。

现在可以运行 `scripts/compare_live_methods.py` 做小批量对照。脚本对同一问题和同一组本地请求，先调用仅文档入口，再运行可查询指标、错误日志和文档的 Agent；两条结果及模型 usage 分开保存。仅文档入口不会拿到当前请求数、p95、超时率或 traceId。默认选择调试集中的 D01、D04、D07、D09 四例，先检查流程与人工审阅标准：

```powershell
python scripts/compare_live_methods.py --allow-model-calls
```

`--all-dev` 才运行全部 10 个调试案例。留出集需要显式 `--split holdout --allow-holdout`，应等调试集的评审标准固定后再首次使用。每例调用同一个已启用模型：仅文档一次，Agent 可能多轮，因此会产生服务商费用。脚本只验证请求和引用结构，输出中的 `manualReview: pending` 必须根据文本和观测单独评审，不能把状态相同当作诊断正确。

人工评审使用固定的[标准 v1](evaluation/rubric-v1.md)：分别检查当前状态、归因边界、引用与范围、不确定性。范围和证据门槛属于应用行为，不能算成模型自主判断。调试集 10 例的[人工记录](validation/2026-09-27-dev-comparison.md)保留了具体越界句子；留出集不用于改提示词。

[留出集首次对照](validation/2026-09-27-holdout-comparison.md)已按标准 v1 完成。仅文档方法保持谨慎，Agent 在得到实时证据后能更具体地回答，但仍有 2 次无效输出和多处措辞越界，其中一次把日志 traceId 抄错。留出集已经使用；修复后的版本需要新案例再作独立检查。

调试中发现：D08 的仅文档模型输出格式无效，D10 的 Agent 在缺少正常状态规则时输出无效。现在明显无关的问题两边共用范围门槛；Agent 对已有观测但缺少对应规则的情况返回证据不足，并记录应用事件。评审表必须把这些应用门槛与模型自行得出的结论分开记。

## LIVE 数据源与模型模式

`scripts/live_model_eval.py` 使用独立订单、库存服务产生的请求，依次检查空窗口、正常和超时三种情况。它要求排障助手同时处于 `MODEL` 模式和 `LIVE` 数据源，运行时需显式传入 `--allow-model-calls`。脚本保存每次执行的工具顺序、引用来源、状态、耗时、模型轮次和服务端 usage；它只自动检查状态与引用结构，结论文字仍需人工核对。

本地接口模拟器用于验证协议，不具备模型选择能力。运行方法：先启动库存和订单服务，再启动 `python scripts/model_protocol_stub.py`，让一个独立排障助手实例使用 `TRIAGE_MODE=MODEL`、`TRIAGE_OBSERVATION_SOURCE=LIVE`、`TRIAGE_MODEL_BASE_URL=http://127.0.0.1:18100`、`TRIAGE_MODEL_API_KEY=test-only-local` 和 `TRIAGE_MODEL_NAME=triage-stub`。运行 `python scripts/live_model_eval.py --allow-model-calls --mock-provider --agent-url http://127.0.0.1:18085`。[本地协议结果](validation/2026-09-27-live-model-stub.json)为 3/3 通过，明确标记 `mockProvider=true`、`modelQualityEvaluated=false`。空窗口在工具返回后由应用证据门槛结束，只调用一轮模型；正常和超时任务各调用两轮。

真实模型验收时，在[模型设置页](http://127.0.0.1:18080/settings.html)自行配置并启用服务，确认首页仍为 LIVE 数据源，再运行：

```powershell
python scripts/live_model_eval.py --allow-model-calls
```

这会提交三个排查任务，每个任务可能多轮调用模型，费用取决于提供商。先用 `--case timeout` 可以只检查一条故障链路。脚本不会保存 API Key 或原始模型对话；完整排查记录和摘要写入 `target/live-model-eval/`。[百炼 LIVE 检查记录](validation/2026-09-27-bailian-live.md)保留了通过的链路、失败尝试和人工审阅发现的措辞问题。三案例只是链路验收；独立对照另见上面的版本化案例与评审记录。
