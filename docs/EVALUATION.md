# 小规模评测

案例集 [`cases-v1.json`](evaluation/cases-v1.json) 有 20 个问题，`dev` 和 `holdout` 各 10 个。两组都包含下游超时、正常、无关问题和缺少匹配排障规则的提问。场景数据是合成的，`expectedDemoStatus` 只用于检查固定规则的演示模式，不能当作模型答案的正确标签。

目前只运行 `dev`。`holdout` 留到模型和对照方法确定后再首次运行；如果根据它的结果修改实现，应另建一组留出案例。公开的案例量很小，不能用来声称通用故障诊断准确率。

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

下一步固定仅检索文档的对照方法，并对同一案例记录工具选择、引用、结果状态、耗时和失败样例。真实模型评测需单独配置服务，记录具体模型与参数；模型返回完整 usage 时才统计 token。普通 CI 不调用付费模型。

## LIVE 数据源与模型模式

`scripts/live_model_eval.py` 使用独立订单、库存服务产生的请求，依次检查空窗口、正常和超时三种情况。它要求排障助手同时处于 `MODEL` 模式和 `LIVE` 数据源，运行时需显式传入 `--allow-model-calls`。脚本保存每次执行的工具顺序、引用来源、状态、耗时、模型轮次和服务端 usage；它只自动检查状态与引用结构，结论文字仍需人工核对。

本地接口模拟器用于验证协议，不具备模型选择能力。运行方法：先启动库存和订单服务，再启动 `python scripts/model_protocol_stub.py`，让一个独立排障助手实例使用 `TRIAGE_MODE=MODEL`、`TRIAGE_OBSERVATION_SOURCE=LIVE`、`TRIAGE_MODEL_BASE_URL=http://127.0.0.1:18100`、`TRIAGE_MODEL_API_KEY=test-only-local` 和 `TRIAGE_MODEL_NAME=triage-stub`。运行 `python scripts/live_model_eval.py --allow-model-calls --mock-provider --agent-url http://127.0.0.1:18085`。 [本地协议结果](validation/2026-09-27-live-model-stub.json)为 3/3 通过，明确标记 `mockProvider=true`、`modelQualityEvaluated=false`。

真实模型验收时，在[模型设置页](http://127.0.0.1:18080/settings.html)自行配置并启用服务，确认首页仍为 LIVE 数据源，再运行：

```powershell
python scripts/live_model_eval.py --allow-model-calls
```

这会提交三个排查任务，每个任务可能多轮调用模型，费用取决于提供商。先用 `--case timeout` 可以只检查一条故障链路。脚本不会保存 API Key 或原始模型对话；完整排查记录和摘要写入 `target/live-model-eval/`。上述协议验收时尚未配置真实模型，因此尚无提供商结果。三案例只是链路验收，20 案例留出集与仅检索对照仍待执行。
