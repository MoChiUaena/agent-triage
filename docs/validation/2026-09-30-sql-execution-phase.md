# SQL 执行阶段判断验收

2026-09-30，主分支提交 `763e336` 通过[常规 CI](https://github.com/MoChiUaena/agent-triage/actions/runs/36688250044)和[公开 Petclinic 验收](https://github.com/MoChiUaena/agent-triage/actions/runs/36688287829)。两套验收都使用实际 JVM 与数据库请求；模型模式使用本地协议模拟器，没有调用付费模型。

数据库样例分别生成正常查询、连接获取超时、释放后的恢复和 SQL 锁等待错误。SQL 错误窗口记录查询失败次数及 `SQL_QUERY_FAILED` 事件，固定规则与本地模型协议均返回 `DB_SQL_EXECUTION_FAILURE_OBSERVED` 对应的成功结果；页面只写“SQL 执行阶段失败”，不写锁等待是已确认根因。获取连接超时继续要求池满、等待线程和同阶段错误，不会被 SQL 事件替代。

Petclinic 的原有 JPA 查询保持正常；验收配置触发的固定无效查询现在单独得到 SQL 阶段失败判断，连接获取超时仍是另一条结果。源码、JPA 包装和模型输入都不保存 SQL 文本或参数。若获取连接错误与 SQL 错误混在同一窗口、计数不一致或缺少对应规则，新判断不会通过证据门槛。旧历史不改写。
