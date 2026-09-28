# 模型判断与结论生成

2026-09-27 的自由文本对照出现了“库存不可用”“服务整体健康”等未被观测证明的说法，也出现了 traceId 误写。新版保留模型的工具选择，但不再让模型编写关键诊断句子。

模型最终只返回三个字段：

```json
{
  "assessment": "DOWNSTREAM_TIMEOUT_OBSERVED",
  "evidenceIds": ["本次指标 ID", "本次日志 ID", "对应规则 ID"],
  "nextChecks": ["INSPECT_INVENTORY_LATENCY", "CORRELATE_TRACE"]
}
```

判断类型只有“本窗口发现库存调用超时”“本窗口未发现库存调用超时”“证据不足”。检查项从固定列表选择。服务端核对窗口请求数、超时率、错误事件和场景规则；方向错误、缺少证据、未知检查项或额外自由文本都会被拒绝。

观察直接采用工具生成的窗口摘要，可能原因和不确定性使用限定范围的应用措辞；模型不复制 p95、请求数或 traceId。具体 traceId 保留在原始日志证据中。成功生成时记录 `CONCLUSION_RENDERED`，页面标明“模型选证据 · 应用生成措辞”。

对外 `diagnosis` 的结构与既有页面保持一致。旧执行记录不重写，页面标为旧版模型文本；昨天的评测仍属于旧版，不能用于证明新版独立效果。新版本使用 [v2 案例](evaluation/cases-v2.json)和[v2 标准](evaluation/rubric-v2.md)验证。
