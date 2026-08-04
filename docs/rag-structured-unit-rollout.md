# 结构化知识单元索引上线手册

## 目标与安全边界

结构化知识单元用于补充召回，不替代原始知识分片。抽取模型生成的 `statement`、问题变体和实体只作为候选信号；回答上下文、直接回答和引用必须始终来自审核通过的原始 `chunk-v3` 分片。

上线过程保持以下约束：

- 抽取结果初始状态只能是 `DRAFT`，未经人工审核不进入独立索引。
- 只有带有效向量且状态为 `APPROVED` 的结构化单元进入 `feisheng_knowledge_semantic_units`。
- 审核通过前，所有 `evidenceChunkIds` 必须属于同一文档且对应原始分片均为 `APPROVED`。
- 检索命中结构化单元后，系统必须再次解析并校验原始证据分片及可信元数据；结构化单元文本本身不得进入回答上下文或引用。
- 一个结构化单元声明的证据必须全部成功回源且内容非空，否则整个单元不得进入融合。
- `categoryId`、`sourceScope`、`expiresAt` 等检索元数据只从当前文档记录注入；模型 metadata 仅作为候选标注，不允许用于线上过滤或策略判断，`risk_level` 固定为 `UNKNOWN`。
- `feisheng_knowledge` 与 `feisheng_knowledge_semantic_units` 必须使用不同的 Qdrant collection。

## 1. 执行数据库迁移

部署新应用前先备份 MySQL，然后执行：

```powershell
docker compose exec -T mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" feisheng_bot_db < /docker-entrypoint-initdb.d/21_add_semantic_knowledge_units.sql'
```

`docker-entrypoint-initdb.d` 只在 MySQL 数据目录首次初始化时自动执行。已有 `mysql_data` 数据卷的环境必须显式运行上面的命令，不能仅靠重启容器。

迁移脚本可重复执行。完成后检查新表和兼容字段：

```powershell
docker compose exec -T mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" feisheng_bot_db -e "SHOW CREATE TABLE bot_knowledge_semantic_unit; SHOW COLUMNS FROM bot_knowledge_chunk;"'
```

迁移文件：`feisheng-bot-parent/sql/21_add_semantic_knowledge_units.sql`。

## 2. 配置模型和参数

在 Admin 的“智能模型”中分别配置并启用：

- `Extraction`：专用于离线结构化 JSON 抽取。建议使用输出稳定、支持 JSON 的小模型，并设为该类型的默认模型；系统不会自动回退到正常客服 `LLM`。
- `Embedding`：用于原始分片和结构化单元向量化。模型输出维度必须与 `QDRANT_VECTOR_SIZE` 一致。

默认运行参数如下：

```dotenv
KNOWLEDGE_STRUCTURED_EXTRACTION_BATCH_SIZE=8
KNOWLEDGE_STRUCTURED_EXTRACTION_MAX_SOURCE_CHARS=8000
KNOWLEDGE_STRUCTURED_EXTRACTION_MAX_UNITS_PER_BATCH=20
QDRANT_SEMANTIC_UNIT_COLLECTION=feisheng_knowledge_semantic_units
RAG_STRUCTURED_UNIT_INDEX_ENABLED=false
RAG_STRUCTURED_UNIT_INDEX_SHADOW_ONLY=true
RAG_STRUCTURED_UNIT_INDEX_TOP_K=5
RAG_STRUCTURED_UNIT_INDEX_WEIGHT=0.65
```

前三项分别限制每批原始分片数、每批最大源文本字符数和每批最多知识单元数。首次上线保留 `ENABLED=false` 和 `SHADOW_ONLY=true`；此时线上检索不调用结构化单元召回。

## 3. 抽取与人工审核

只对已完成入库的文档执行抽取。抽取前确认原始分片内容和向量完整：

1. 调用 `POST /api/admin/knowledge/semantic-unit/extract/{documentId}` 生成草稿。请求体可选传入 `{"preferredModelId": 123}`，且该模型必须是已启用的 `Extraction` 类型。
2. 调用 `GET /api/admin/knowledge/semantic-unit/list?documentId={documentId}&status=DRAFT` 检查每条单元的陈述、条件、排除项、查询变体、原文引用和 `embeddingReady`。
3. 核对引用文字与原文完全一致，数字、ID、产品名、否定语义和适用范围没有被模型发明、丢失或扩大。
4. 确认所有证据原始分片已经审核为 `APPROVED`，再调用 `POST /api/admin/knowledge/semantic-unit/{unitId}/approve`，可提交 `{"reason":"已核对原文"}` 作为审核备注。
5. 不可靠的草稿调用 `POST /api/admin/knowledge/semantic-unit/{unitId}/reject`，请求体必须包含非空原因，例如 `{"reason":"适用条件不完整"}`。原文变化后重新抽取，不沿用旧草稿。

审批接口仅允许 `ADMIN` 角色访问，并以条件更新防止并发通过/拒绝互相覆盖。审核人 ID、审核时间和原因会写入语义单元记录，并在管理端详情中展示。

批准和拒绝会触发结构化单元索引同步；定时同步也会按 `RAG_INDEX_SYNC_INTERVAL_MS` 对账。只有审核通过、证据有效、文档已完成且向量完整的单元才会写入独立 collection。

## 4. 分阶段启用

### Disabled

```dotenv
RAG_STRUCTURED_UNIT_INDEX_ENABLED=false
RAG_STRUCTURED_UNIT_INDEX_SHADOW_ONLY=true
```

先完成迁移、模型配置、少量文档抽取和人工审核。此阶段结构化索引的启动、定时同步、手工同步和检索都会短路，不访问语义单元数据库查询、Embedding JSON 或 Qdrant；现有 RAG 行为保持不变，状态接口返回 `disabled`。

使用管理员令牌调用 `GET /api/admin/knowledge/semantic-unit/index/status` 查看独立索引状态。该接口只读取状态，不触发同步或写入；Disabled 阶段的 `searchBackend` 应为 `disabled`、`units` 应为 `0`、`qdrantReady` 应为 `false`。

### Shadow

```dotenv
RAG_STRUCTURED_UNIT_INDEX_ENABLED=true
RAG_STRUCTURED_UNIT_INDEX_SHADOW_ONLY=true
```

用固定评测集和真实低风险流量观察结构化召回诊断。Shadow 命中只进入诊断数据，不改变候选排序、Rerank、回答上下文或引用。至少覆盖：明确问法、口语改写、带产品/地区/渠道元数据的问题、无答案问题和高风险问题。

### Active

```dotenv
RAG_STRUCTURED_UNIT_INDEX_ENABLED=true
RAG_STRUCTURED_UNIT_INDEX_SHADOW_ONLY=false
```

只在 Shadow 指标稳定且人工抽检通过后小流量启用。Active 模式仍只能把重新校验后的原始 `chunk-v3` 证据加入融合；每个语义单元必须完整解析全部证据，线上全局检索固定使用服务端注入的 `sourceScope=KNOWLEDGE`。`RAG_STRUCTURED_UNIT_INDEX_WEIGHT=0.65` 是融合权重，不能视为置信度阈值。逐步扩大流量前，每一档都重新跑同一评测集并抽检引用。

## 5. 观测与验收

上线期间至少记录并对比：

- 原检索与结构化检索的 Recall@K、命中重合率和新增有效命中率。
- `structuredUnitDiagnostics` 中的单元 ID、原始分数、排名和运行模式。
- 结构化命中成功解析为原始证据的比例，以及因分片状态、文档状态或元数据不匹配被拒绝的比例。
- Rerank 的 HIGH、MEDIUM、LOW 分层占比、无答案率、转人工率和错误引用率。
- Qdrant 两个 collection 的向量维度、point 数、同步错误和延迟；主 collection 的指标不得被独立 collection 污染。
- 抽取批次的 `SUCCESS`、`PARTIAL`、`FAILED`，草稿审核通过率，以及模型输出校验失败原因。

发布验收条件：Shadow 不改变基线回答；Active 的有效召回提升可复现；任何回答引用都能回溯到当前审核通过的原始分片；禁用结构化召回后基线行为立即恢复。

## 6. 回滚

出现排序退化、错误引用、超时或 Qdrant 独立 collection 异常时，先将：

```dotenv
RAG_STRUCTURED_UNIT_INDEX_ENABLED=false
RAG_STRUCTURED_UNIT_INDEX_SHADOW_ONLY=true
```

重启应用使配置生效。该操作只关闭结构化召回，不删除草稿、审核记录、原始分片或独立 collection，便于离线排查后重新进入 Shadow。

如果仅 Active 融合表现异常，可先保留 `ENABLED=true` 并切回 `SHADOW_ONLY=true` 收集诊断。不要通过删除 `chunk-v3`、降低原始分片审核状态或复用主 collection 来回滚。数据库迁移增加的表和兼容字段应保留；除非已完成备份、影响评估和专门的数据回退方案，不执行逆向 DDL。
