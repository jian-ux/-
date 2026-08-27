# 智能客服第二阶段优化

## 已实现

### 敏感信息保护

- 用户消息在入库、RAG 检索、模型提示词、未命中记录和 AI 日志前脱敏。
- FAQ、模型回答、引用和候选结果在返回前再次脱敏。
- 当前识别手机号、身份证、银行卡、邮箱和带明确标签的地址。
- 响应仅返回 `redactionApplied` 与 `redactedTypes`，不返回原始敏感值。
- `channelUserId` 不参与脱敏，避免破坏跨轮会话连续性。
- 官方热线白名单由 `SECURITY_PII_ALLOWED_VALUES` 配置，多个值用逗号分隔。

### 转人工与工单

- 安全规则、模型失败、AI 输出拦截和低置信度回答会触发真实转人工。
- 无知识答案默认只回复兜底并记录问题；需要自动转人工时可显式开启对应配置。
- 同一会话的 `pending` 或 `processing` 工单会被复用，避免重复创建。
- 工单创建与会话状态更新在同一事务中完成，并对会话行加锁。
- 工单描述包含转人工原因和最近十条消息的脱敏摘要。
- `P0`、`P1`、`P2`、`P3` 默认 SLA 分别为 30 分钟、2 小时、8 小时和 24 小时。
- 手动 `/api/core/conversation/transfer` 与自动转人工使用同一条工单链路。

关键配置：

```properties
RAG_NO_ANSWER_TRANSFER=false
RAG_HANDOFF_LOW_CONFIDENCE_THRESHOLD=0.55
SECURITY_PII_ALLOWED_VALUES=18689633999
```

### 端到端回复评测

接口：`POST /api/admin/rag/evaluate-dialog`

评测会运行真实 `DialogServiceImpl`，覆盖历史消息、业务工具、RAG、模型、安全检查、脱敏和转人工。每条样本使用独立事务，返回结果前强制回滚会话、消息、工单、工具审计、未命中记录和 AI 日志。

数据库数据可以回滚，外部模型调用和 API 成本不能回滚。建议先使用小样本验证，再运行完整评测集。单次最多 100 条。

指标包括：

- 决策准确率
- 意图识别准确率
- 独立检索问题改写准确率
- 知识依据一致率
- 必须短语命中率
- 禁止短语违规数
- 转人工准确率
- PII 泄漏数
- 模型错误数

请求样例见 `docs/examples/dialog-evaluation.json`。原有 `docs/examples/rag-evaluation.json` 的基础字段也兼容该接口。

单条样本可选填 `expectedIntentCode` 和 `expectedStandaloneQuery`。填写后，报告会返回对应的 `intentAccuracy`、`queryRewriteAccuracy` 及逐题匹配结果；不填写时不会影响旧评测集的通过判定。

可在请求顶层增加 `promptVersion: "v1"` 或 `promptVersion: "v2"`，对单次评测指定客服 Prompt；不传时使用生产默认配置。执行 `scripts/compare-dialog-prompts.ps1` 可用同一批样本依次运行 V1/V2，并保存逐题差异和指标对比。生产默认由 `AI_CUSTOMER_SERVICE_PROMPT_VERSION` 控制，当前使用 `v2`。
