# 多下游 HTTP 验收（2026-10-12）

验证目标：结算服务同一请求调用库存和支付时，库存超时不会把支付写成故障。

独立样例 `verification/multi-downstream-service` 使用 Boot `RestTemplateBuilder` 调用两个真实回环 HTTP 服务。库存服务在 350 ms 后响应，客户端读取时限为 100 ms；支付服务先返回 200，再返回 500。两个入站请求分别得到 `stock=unavailable;payment=ok` 和 `stock=unavailable;payment=failed`。Starter 的 V5 窗口记录总请求 2、库存超时 2、支付超时 0，支付分项的 2xx 与 5xx 各为 1。样例测试由 `integrations/pom.xml` 的 `verify` 运行。

Agent API 测试使用本机 V5 观测服务，覆盖启动配置、`/api/config`、排查提交、冻结证据和历史快照。指标证据分开保存库存超时与支付 5xx；固定规则仍只把超时归于库存。模型输出测试验证同一结论的应用生成措辞，并拒绝把零超时但有 5xx 的窗口写成完成判断；未调用外部模型，也未使用真实密钥。DOM 测试核对两个下游行、响应分类和旧历史显示。

本机检查：主项目 `verify`、Starter `verify`、integrations `verify`、`node scripts/multi_downstream_ui_check.js`、`node scripts/inbound_ui_check.js` 均退出 0。V5 发布附件和第三方业务项目验收仍待后续阶段；Windows 小时级宿主调度问题未纳入本次功能验收，也没有放宽原有门槛。

复核补充：多下游配置必须同时开启接口观测，否则 V1 和 V5 路由都不可用。Starter 现在在启动时拒绝这一组合。V5 按每个入站请求保留每个下游最高的已观察响应类别；收到 2xx 响应头后正文仍可能超时，零超时也不能当作成功响应。

公开项目复验使用官方 `spring-petclinic-rest` v3.4.0 的固定提交 `26a80b7a510af1fda0acdfb45d65aa480bdb7607`。隔离副本仅修改 POM 并加入默认关闭的验收控制器，原有业务类保持原样；Starter 和 Agent 使用同一工作树构建版本。两个真实请求分别让支付返回 200 和 500，同时库存读取均超时。Petclinic REST 的 V5 窗口为总请求 2、库存超时 2、支付超时 0、支付 2xx/5xx 各 1；Agent 冻结的证据和历史保留相同分项，诊断只把超时归于库存。全程固定规则模式，无外部模型调用。运行命令为 `python scripts/petclinic_multi_downstream_smoke.py --project-directory <已准备的固定提交检出>`；结果与日志留在忽略目录 `target/petclinic-multi-acceptance/`，不提交外部项目副本或原始请求数据。公开项目的完整长期负载验收仍未进行。

两个下游调用来自验收辅助控制器，原项目的主人、诊疗等业务路由没有被改造成多下游流程；这次验证的是第三方应用的构建、组件接入、真实 HTTP 归属和 Agent 读取链路。
