# 跨线程观测验收

2026-10-01，[完整 CI](https://github.com/MoChiUaena/agent-triage/actions/runs/36829941946)在 Windows 和 Ubuntu 通过主项目 272 项、Starter 42 项测试。新增检查覆盖 Runnable / Callable 的显式包装、任务异常后的线程恢复、空快照隔离、8 个线程的并发累计、首次异常保留，以及正在运行的调用在请求完成后返回时不再修改观测。WebAsyncTask 使用自定义执行器的路径也已核对。JPA 包装拒绝已完成请求的迟到查询记录，业务查询照常执行。

[公开 Petclinic REST 验收](https://github.com/MoChiUaena/agent-triage/actions/runs/36829069498)沿用固定上游提交和原 REST 业务文件。默认关闭的辅助入口分别返回 MVC Callable 和 DeferredResult：Callable 开启可选传播，DeferredResult 显式包装生产任务，两者的实际本机 HTTP 超时均归属原接口窗口。两次并发的 150ms 超时调用累计耗时至少 250ms，但请求数和超时请求数各为 1。另一入口先返回 204，后台调用随后超时；该接口仍只有一次 2xx，请求超时数、下游耗时和错误事件均保持为零。原有 404、5xx、同步超时、源码版本核对和旧历史快照检查继续通过，全程没有调用模型。

自动接入范围限定为 Spring MVC 管理的 Callable / WebAsyncTask，并由 `async-context-propagation` 显式开启。应用其他执行器、DeferredResult 或 CompletableFuture 任务需要主动捕获和包装；WebFlux、分布式链路和任意线程自动传播未覆盖。快照不复制请求头、正文或业务参数，任务结束后恢复线程原状态。请求完成只停止接收观测，不取消后台业务任务。并发下游耗时是调用耗时之和，不能当作请求关键路径；错误位置保留首次捕获结果。
