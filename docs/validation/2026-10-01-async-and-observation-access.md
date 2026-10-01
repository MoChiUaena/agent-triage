# 异步请求与观测访问验收

2026-10-01，Starter 的 Spring MVC 异步测试先复现了漏计：`DeferredResult` 完成后，观测窗口仍是 0 次请求。修复后，请求在 Servlet 完成回调时计入一次；等待时间进入请求耗时，最终 503 状态生成一条错误事件，正常完成的请求不生成错误。Starter 全套 29 项测试、主项目 265 项测试通过，主项目另有 1 项 Windows 符号链接权限测试跳过。[上一轮跨平台 CI](https://github.com/MoChiUaena/agent-triage/actions/runs/36803740869)已覆盖异步改动。

[独立观测令牌验收](https://github.com/MoChiUaena/agent-triage/actions/runs/36807132130)使用一个商品应用和两个 Agent 进程；[完整 CI](https://github.com/MoChiUaena/agent-triage/actions/runs/36807105557)也通过同一脚本。商品应用保持只监听本机，未带令牌和错误令牌的请求返回 403；轮换窗口中，使用新旧令牌的两个 Agent 均可读取空观测。移除旧令牌并重启商品应用后，旧 Agent 变为不可用，新 Agent 仍可读取。数据库观测路由在未授权时同样返回 403。测试令牌临时生成，配置写入被 Git 忽略的 `target/`，结束时删除；仓库没有保存原始令牌或运行 JSON。

异步工作线程内的下游调用仍未继承请求上下文，不能把这次验收理解为完整异步调用链。观测令牌只保护已登记服务的本机只读观测接口，不提供多用户登录，也不开放非回环地址；轮换每一步需要重启相应进程。当前 main 的这两项改动尚未包含在 v0.6.0 发布包中。
