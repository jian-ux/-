# RAG 第二阶段：质量评测、引用、无答案与同步

## 交付范围

第二阶段将 RAG 从“能够检索”收口为可评测、可解释、可拒答、可同步的线上链路：

- FAQ 和已审核文档块使用统一检索与阈值策略。
- 高置信 FAQ 直接回答，中置信依据交给 LLM 生成，低置信统一拒答。
- 所有知识回答返回结构化引用，并在文本末尾附来源，渠道侧无需额外适配。
- 无答案问题写入 `bot_unmatched_question`，相同未解决问题会累加次数。
- 向量索引使用原子快照同步，失败时保留上一可用版本。
- 离线评测复用线上检索服务，不更新 FAQ 命中次数。

## 回答契约

对话接口保留原字段，并新增：

| 字段 | 类型 | 说明 |
|---|---|---|
| `answerStatus` | string | `answered`、`no_answer`、`blocked` 或 `error` |
| `confidence` | number | 当前最佳检索分数，范围 0-1 |
| `citations` | array | FAQ 或文档块的结构化来源 |
| `retrieval.decision` | string | `direct`、`rag`、`no_answer` 或 `provided_context` |
| `retrieval.candidates` | array | 调试用候选及向量、关键词、综合分 |

引用示例：

```json
{
  "ref": 1,
  "id": "chunk:23",
  "sourceType": "document",
  "sourceId": 23,
  "documentId": 8,
  "chunkIndex": 2,
  "title": "员工手册",
  "score": 0.764,
  "snippet": "员工每年享有五天年假……"
}
```

## 决策策略

- `score >= RAG_DIRECT_THRESHOLD` 且最佳来源是 FAQ：直接回答。
- `score >= RAG_CONTEXT_THRESHOLD`：构造最多 `RAG_TOP_K` 条依据，由 LLM 回答并引用。
- `score < RAG_CONTEXT_THRESHOLD`：不调用 LLM，返回固定无答案文案并记录问题。
- 文档块以向量相似度作为综合分；FAQ 在关键词与向量均命中时取更可靠的分数。

默认配置：

```dotenv
RAG_TOP_K=3
RAG_DIRECT_THRESHOLD=0.82
RAG_CONTEXT_THRESHOLD=0.50
RAG_NO_ANSWER_TRANSFER=false
RAG_INDEX_SYNC_INTERVAL_MS=30000
```

## 管理接口

所有接口均位于现有管理员鉴权范围 `/api/admin/rag`：

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/status` | 查看 FAQ、文档块向量回填状态 |
| `POST` | `/backfill` | 回填缺失向量，完成后同步索引 |
| `GET` | `/sync-status` | 查看索引版本、向量数及最近同步结果 |
| `POST` | `/sync` | 手动执行数据库到内存索引的对账同步 |
| `POST` | `/evaluate` | 执行只读离线质量评测 |

知识新增、更新、删除、向量生成、文档入库、分块审核或驳回后会立即同步；定时同步负责兜底外部数据库变更。

## 质量评测

评测请求样例位于 `docs/examples/rag-evaluation.json`。每条样本至少包含：

- `question`：测试问题。
- `answerable`：知识库是否应该回答。
- `expectedSourceType`：可选，`faq` 或 `document`。
- `expectedSourceId`：可选，期望 FAQ ID 或文档块 ID。
- `history`：可选，多轮对话历史；每条包含 `role` 和 `content`。历史仅用于语义检索，关键词匹配仍只使用当前 `question`。

报告指标：

- `decisionAccuracy`：应答/拒答决策准确率。
- `answerRecall`：应回答样本被正确接受的比例。
- `noAnswerRecall`：应拒答样本被正确拒答的比例。
- `citationHitRate`：声明期望来源的样本中，引用命中的比例。
- `answerPrecision`：系统实际回答中，应回答样本的比例。
- `noAnswerPrecision`：系统实际拒答中，应拒答样本的比例。
- `sourceHitAtOneRate`：期望来源排在第一位的比例。
- `meanReciprocalRank`：期望来源排名倒数的平均值。

调阈值时应固定一份业务评测集，比较同一批样本的四项指标，并重点审阅 `cases` 中的错误明细。生产验收建议同时关注应答召回和无答案召回，避免只追求命中率而放大幻觉。

该接口评测的是检索决策和引用，不评判最终大模型回复的措辞与事实一致性。方言、错别字、多诉求、情绪和任务执行仍应在端到端客服评测中单独验收。
