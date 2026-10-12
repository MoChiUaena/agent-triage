# 多下游 HTTP 验收（2026-10-12）

验证目标：结算服务同一请求调用库存和支付时，库存超时不会把支付写成故障。

独立样例 `verification/multi-downstream-service` 使用 Boot `RestTemplateBuilder` 发出两个真实回环 HTTP 请求。库存服务在 350 ms 后响应，客户端读取时限为 100 ms；支付服务立即返回 `ok`。同一入站请求返回 `stock=unavailable;payment=ok`。Starter 的 V5 窗口记录总请求 1、总超时 1、库存超时 1、支付超时 0。样例测试由 `integrations/pom.xml` 的 `verify` 运行。

Agent API 测试使用本机 V5 观测服务，覆盖启动配置、`/api/config`、排查提交、冻结证据和历史快照。问题询问支付服务时，固定规则仍按已验证分项把超时归于库存，诊断不宣称支付故障。模型输出测试验证同一结论的应用生成措辞；未调用外部模型，也未使用真实密钥。DOM 测试核对两个下游行和旧历史显示。

本机检查：主项目 `verify`、Starter `verify`、integrations `verify`、`node scripts/multi_downstream_ui_check.js`、`node scripts/inbound_ui_check.js` 均退出 0。V5 发布附件和第三方业务项目验收仍待后续阶段；Windows 小时级宿主调度问题未纳入本次功能验收，也没有放宽原有门槛。

复核补充：多下游配置必须同时开启接口观测，否则 V1 和 V5 路由都不可用。Starter 现在在启动时拒绝这一组合。V5 当前只区分超时和耗时，不采集每个下游的 HTTP 2xx/5xx 分类；零超时不能当作成功响应。
