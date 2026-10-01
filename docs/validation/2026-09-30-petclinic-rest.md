# Petclinic REST 接入验收

2026-09-30，[GitHub Actions 验收](https://github.com/MoChiUaena/agent-triage/actions/runs/36728315487)通过。上游使用 `spring-petclinic/spring-petclinic-rest` v3.4.0 的固定提交 `26a80b7a510af1fda0acdfb45d65aa480bdb7607`。工作流重新克隆并构建项目；准备脚本只修改副本的 POM，并加入默认关闭的验收辅助类，原有 REST 业务文件保持原样。应用继续使用 `/petclinic/` 上下文路径。

验收向原有 `/api/owners/1`、`/api/owners` 和不存在的主人详情发送 HTTP 请求。Agent 从固定的观测路由读取接口窗口，主人详情记录 3 次请求；源码图将该接口匹配到 `OwnerRestController`，构建摘要与索引源码一致。另一个只在验收时启用的接口抛出异常，Petclinic 自带的全局处理器仍返回 500；Starter 在处理器接手前记录异常位置，辅助类栈帧与构建摘要匹配。修改隔离副本中的源码并重建索引后，新排查标为源码版本不同，旧历史保持原来的分析结果。全程使用固定规则模式，没有调用模型。

可通过 `gh workflow run petclinic-rest.yml --ref main` 重跑；工作流使用 [准备脚本](../../scripts/prepare_petclinic_rest.py) 和 [验收脚本](../../scripts/petclinic_rest_smoke.py)。本机运行输出放在被忽略的 `target/`，未提交原始排查 JSON 或外部项目副本。

这次的异常来自验收辅助接口，不能代表 Petclinic 原有业务接口存在该故障。404 响应没有异常位置；静态源码图也不等于实际调用轨迹。

2026-10-01 的[补充验收](https://github.com/MoChiUaena/agent-triage/actions/runs/36808128152)单独核对了原有主人详情接口的 404。两次正常请求和一次不存在的主人详情合计进入该接口的 3 次请求计数，但错误日志没有 404 事件。直接询问“为什么返回 404”时，固定规则模式返回证据不足，没有把“主人不存在”、路由错误或鉴权问题写成根因。该次验收时尚未采集响应分类。后续 [v0.9 验收](2026-10-01-http-response-classes.md)增加了分类计数和带证据的有限说明；判断具体状态码的根因仍缺少业务上下文。

Spring MVC 异步请求的完成计数和本机观测令牌已有独立验收；后续[跨线程验收](2026-10-01-cross-thread-observations.md)补充了 Callable 的可选传播和显式包装任务的下游调用。其他 HTTP 客户端、多用户鉴权和多实例协调仍未覆盖。
