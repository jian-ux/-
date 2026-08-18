# RAG 回答质量门禁

## 作用

`POST /api/admin/rag/evaluate` 现在同时返回：

- 评测集版本、检索流水线版本和本次运行 ID。
- 向量、BM25、词法、RRF 和 Rerank 的候选分数与最终排名。
- 候选是否进入答案、是否进入模型上下文，以及淘汰原因。
- 本次运行使用的关键阈值快照。
- 每项最低指标是否通过，以及总门禁结果 `releaseGatePassed`。

当 Rerank 对纠错型问题整体低置信时，系统不会降低全局阈值。只有唯一一份
已审核结构化答案同时满足以下条件，才会通过受控兜底进入上下文：词面分数达到
`RAG_STRUCTURED_QA_ANSWER_FALLBACK_LEXICAL_MIN_SCORE`，且答案完整覆盖客户问题中
明确出现的价格、时长或数量事实。多份答案同时命中或数值不一致时仍然拒答。
该路径在报告中显示为原因码 `STRUCTURED_ANSWER_FACT_FALLBACK` 和置信度来源
`reviewed_structured_answer_fallback`，便于单独监控。

门禁只评测检索和证据选择。最终回复内容、禁止词、PII 和转人工仍使用
`POST /api/admin/rag/evaluate-dialog` 进行端到端评测。

## 运行

先启动后台服务，再执行：

```powershell
.\scripts\run-rag-quality-gate.ps1 `
  -BaseUrl http://localhost:8082 `
  -DatasetPath .\docs\examples\dianqian-doc-rag-evaluation.json `
  -OutputPath .\docs\evaluation-results\rag-quality-gate-latest.json
```

脚本默认从项目根目录的 `.env` 读取 `ADMIN_USERNAME` 和 `ADMIN_PASSWORD`
并调用登录接口获取短期 JWT；也可以通过同名进程环境变量覆盖。

指标未达到数据集中的 `qualityGate` 时，脚本退出码为 `1`，可直接接入 CI
或部署流水线。通过时退出码为 `0`。

## 数据集字段

```json
{
  "name": "customer-service-release",
  "datasetVersion": "customer-service-v1",
  "qualityGate": {
    "minDecisionAccuracy": 0.95,
    "minAnswerRecall": 0.90,
    "minNoAnswerRecall": 0.90,
    "minCitationHitRate": 0.95,
    "minAnswerPrecision": 0.95,
    "minNoAnswerPrecision": 0.90,
    "minSourceHitAtOneRate": 0.85,
    "minMeanReciprocalRank": 0.90
  },
  "cases": []
}
```

单条样本可选填 `expectedDecision`。支持实际检索决策值 `direct`、`rag`、
`structured_qa_direct`、`structured_table_direct`、`no_answer`，也支持通用值
`ANSWER`、`NO_ANSWER` 和 `NO_KNOWLEDGE`。

每次修改知识、切片、Embedding、Rerank、融合权重或阈值时，应同步修改
`RAG_PIPELINE_VERSION`，使评测报告可以准确对应到运行配置。
