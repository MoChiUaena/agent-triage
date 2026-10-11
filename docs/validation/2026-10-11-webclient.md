# WebClient 出站观测验收

代码提交 `5f9366ce40e474bbe542bd251a55cd4f96ee7b5b` 接入 Boot 提供的 `WebClient.Builder`；`cf6931f3683011a01f5f2b246fe2c8fdae1d957d` 增加独立商品服务测试。[Verify](https://github.com/MoChiUaena/agent-triage/actions/runs/38142840266) 的 Windows、Linux、PostgreSQL 和本地模型协议作业通过；[容量检查](https://github.com/MoChiUaena/agent-triage/actions/runs/38142840224) 双平台通过。源码中的功能尚未放入已发布的 v0.11.0 附件。

商品服务测试启动真实 Servlet 应用和回环下游。正常请求返回 200；响应头和正文读取超时分别返回 504。只读观测接口记录 3 次请求、2 次下游超时，并保留相应 traceId；接口结果不含测试用查询参数或响应正文。Starter 自身另核对连接拒绝、5xx、取消、跨线程订阅、请求结束后才订阅，以及没有 WebFlux 依赖时的启动。

`spring-webflux` 在 Starter POM 中为可选依赖；商品应用的 WebFlux 仅用于测试，构建后的商品 JAR 未包含 `spring-webflux` 或 Reactor Netty。已有 RestTemplate 在 Reactor Netty 出现在类路径时也通过响应头、正文超时回归。过滤器不保存 URL、请求头或正文，不改变客户端设置的连接器。WebFlux 服务端、自行创建的 WebClient 和 Feign 仍未覆盖；本轮没有验证真实远程网络或长期吞吐。
