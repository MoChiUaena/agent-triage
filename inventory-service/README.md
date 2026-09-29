# 库存服务样例

独立的 Spring Boot 服务，默认监听 `127.0.0.1:18084`。订单服务通过 HTTP 调用 `GET /api/inventory/{sku}`。正常场景等待约 15ms；`DOWNSTREAM_TIMEOUT` 场景等待 600ms，使订单客户端的 300ms 请求时限到期。

```powershell
.\mvnw.cmd -f inventory-service/pom.xml verify
java -jar inventory-service/target/triage-inventory-service-0.3.0.jar
```

`GET /actuator/metrics/sample.inventory.duration` 可查看 Micrometer 记录的处理时间。实验页面通过订单服务切换库存场景；也可直接使用 `POST /lab/scenario`。此服务默认仅监听本机地址。
