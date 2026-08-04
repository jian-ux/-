# RAG 第七阶段：混合检索、重排与索引版本治理

## 交付范围

- 在现有 Qdrant 语义召回之外增加内存 BM25 稀疏召回。
- 使用 RRF 融合向量、BM25、词面、拼音和 FAQ 关键词排名。
- 可选调用 Cross-Encoder 重排前 10 条候选；未配置、超时、失败或返回不完整时保留 RRF 顺序。
- BM25 和重排只决定候选顺序，现有证据阈值继续决定是否回答。
- 向量记录增加模型、版本、维度和内容哈希，索引状态报告模型版本一致性。
- 离线评测增加回答/拒答精确率、来源 Top-1 命中率和 MRR。

## 数据库迁移

部署新代码前执行：

```powershell
Get-Content -Raw -Encoding utf8 feisheng-bot-parent/sql/15_add_embedding_metadata.sql |
  docker exec -i -e MYSQL_PWD=<MYSQL_PASSWORD> feisheng-mysql mysql -uroot
```

迁移只增加元数据列，不修改已有向量。已有向量会暂时显示为 `legacyVectors`。

## 安全启用顺序

1. 保持 `RAG_REQUIRE_CONSISTENT_EMBEDDING_VERSION=false`，部署并启动应用。
2. 调用 `POST /api/admin/rag/re-embed-all`，用当前 Embedding 模型重算所有有效 FAQ 和已审核 chunk。
3. 查询 `GET /api/admin/rag/sync-status`，确认 `embeddingConsistency.consistent=true`、`legacyVectors=0`。
4. 调用 `POST /api/admin/rag/qdrant/reindex`，确认 Qdrant point 数与有效向量数一致。
5. 将 `RAG_REQUIRE_CONSISTENT_EMBEDDING_VERSION=true` 后重启。以后检测到混合模型版本时保留上一可用索引。
6. 先保持 `RAG_RERANK_ENABLED=false` 运行固定评测；配置并验证重排模型后再灰度开启。

## 检索顺序

```text
精确 FAQ --------------------------------------> 直接回答
非精确问题 -> 向量/BM25/词面/拼音/关键词召回
           -> RRF 排名融合
           -> Cross-Encoder（可选）
           -> 原证据阈值过滤
           -> Top-3 证据、引用与 LLM
```

BM25 原始分和 Cross-Encoder 分不参与 `RAG_CONTEXT_THRESHOLD` 比较，避免把不同分布的分数当成统一置信度。

## 配置

```dotenv
RAG_BM25_ENABLED=true
RAG_BM25_MIN_SCORE=0.0
RAG_RANK_FUSION_K=60
RAG_RERANK_ENABLED=false
RAG_RERANK_URL=
RAG_RERANK_API_KEY=
RAG_RERANK_MODEL=
RAG_RERANK_CONNECT_TIMEOUT_MS=1000
RAG_RERANK_READ_TIMEOUT_MS=3000
RAG_RERANK_MAX_CANDIDATES=10
RAG_RERANK_MAX_DOCUMENT_CHARS=2000
RAG_REQUIRE_CONSISTENT_EMBEDDING_VERSION=false
```

重排接口使用 `POST /rerank`，请求字段为 `model`、`query`、`documents` 和 `top_n`；响应接受 `results` 或 `data` 数组，每项包含 `index` 以及 `relevance_score` 或 `score`。

## 评测指标

- `answerPrecision`：系统实际回答中，应回答样本的比例。
- `noAnswerPrecision`：系统实际拒答中，应拒答样本的比例。
- `sourceHitAtOneRate`：期望来源排在第一位的比例。
- `meanReciprocalRank`：期望来源排名倒数的平均值。

这些指标补充原有 `decisionAccuracy`、`answerRecall`、`noAnswerRecall` 和 `citationHitRate`，仍不替代最终答案忠实度与人工验收。
