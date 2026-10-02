# 入站 HTTP V4 验收

2026-10-02，增加 `HTTP_REQUESTS` 模式和 `HTTP_REQUESTS_V4` 协议。它们不要求下游身份或 origin，保留请求、接口、可选响应分类、错误位置和源码核对；窗口与接口摘要不输出下游耗时或超时计数。配置说明见[入站请求接入](../INBOUND_HTTP.md)。

[最终常规 CI](https://github.com/MoChiUaena/agent-triage/actions/runs/36983605148)在 Windows/Linux 通过主项目 284 项、Starter 58 项，以及 PostgreSQL、实际样例、本地模型协议和浏览器渲染检查；[容量](https://github.com/MoChiUaena/agent-triage/actions/runs/36983605200)与[五分钟资源回归](https://github.com/MoChiUaena/agent-triage/actions/runs/36983605194)也通过。对应提交为 `57e517a07fde0b644f8d3fe3a6c76d7ef4a324bd`。本机 Windows 有一项符号链接权限检查跳过，CI 两平台没有跳过。

新增回归覆盖无下游配置启动、基本请求窗口、接口选择、可选 JPA、令牌和回环限制、容量及过期边界、未知响应、严格拒绝混入下游字段、同窗口计数、接口来源与历史回读。模型按一轮或两轮收集请求证据，应用返回证据不足，不再请求最终成功判断；路由和处理方法仍从运行模型输入中移除。另用带假零值的入站证据验证旧“无下游超时”判断被拒绝，移除门槛时该回归确实失败。

页面检查运行实际 `app.js` 渲染函数，确认新模式显示“未采集”，旧模式的真实零超时保持零；同时检查脚本语法。这是 DOM 行为回归，没有重新调整布局或做视觉对照。

[固定 Petclinic](https://github.com/MoChiUaena/agent-triage/actions/runs/36983817409)依次验收旧 V3、旧 V3 加 JPA、新 V4 和新 V4 加 JPA。原有正常页、主人详情、异常、请求计数、源码入口、错误位置、构建摘要差异和旧历史均通过；新模式没有填写占位下游配置，JPA 的 SQL 阶段和连接获取检查仍通过。

首次新增检查曾[失败](https://github.com/MoChiUaena/agent-triage/actions/runs/36982867705)：脚本把两次逃逸异常预期为已采集 5xx。实际过滤器记录时容器尚未生成最终错误页，因此保留两个未知响应。先增加独立回归核对，再修正脚本：主人详情四次请求为两个成功、两个未知，而客户端确实收到两次 500。保留这一采集边界，不倒填成已知 5xx。

[Petclinic REST](https://github.com/MoChiUaena/agent-triage/actions/runs/36982872872)保持旧 V3 配置，原接口、响应分类、Callable、显式包装的 DeferredResult、并发与迟到调用、源码版本检查通过。它运行于 `b4663b02e97a097f88d2aa1587e336af66e5cc62`；后续提交仅补充逃逸异常测试和 Petclinic 的断言，应用代码相同。

这一阶段只完成入站观测，正常与错误请求都不据此形成下游、数据库或请求失败阶段的成功判断。下一项按同窗口响应、错误与规则建立请求执行阶段失败的门槛。完整调用链、具体 404 业务根因、其他客户端和数天运行仍未覆盖。所有模型调用使用本地协议服务与测试凭据；原始 JSON、日志、数据库和公开项目副本留在忽略目录，既有发布标签和附件不变。
