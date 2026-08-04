# RAG 第五阶段：接入 Qdrant

## 交付范围

第五阶段将生产语义检索从 JVM 全量余弦扫描切换到 Qdrant，同时保留 MySQL embedding 和内存快照作为迁移源及故障回退：

- Qdrant 使用 `Cosine` 距离和 `2048` 维向量，对应智谱 `embedding-3`。
- 应用启动时从 MySQL 全量读取有效向量，与 Qdrant collection 对账。
- 后续知识条目、文档 chunk 和审核状态变化继续通过既有 `sync()` 触发增量 upsert/delete。
- Qdrant 请求失败后，本次检索立即使用内存余弦检索，下一次同步自动执行全量修复。
- 上层 RAG、引用、图片预览、无答案策略和前端返回结构不变。
- Docker Compose 增加持久化 Qdrant 服务、健康检查和应用启动依赖。

本阶段不删除 MySQL 中的 embedding JSON。多模态统一检索和语音回复属于第六阶段。

## 检索链路

```text
RagRetrievalService
  -> KnowledgeClient.semanticMatch
  -> KnowledgeItemController.semanticMatch
  -> KnowledgeIndexService.search
       -> QdrantVectorStore.search（主路径）
       -> immutable memory snapshot（故障回退）
```

Qdrant point ID 根据 `item:{id}` 或 `chunk:{id}` 生成确定性 UUID。payload 保存现有 RAG 所需的完整元数据：

- FAQ：`type`、`sourceType`、`sourceId`、`itemId`、问题、答案和内容。
- 文档 chunk：`chunkId`、`documentId`、`chunkIndex`、标题、媒体类型和内容。
- 图片 chunk 额外保存 `sourceType=image` 和 `previewUrl`。

检索结果只在 payload 上补充 Qdrant 返回的 `similarity`，因此上层调用方无需区分后端。

## 同步规则

启动首次同步执行全量对账：

1. 查询状态为启用且 embedding 非空的 FAQ。
2. 查询状态为 `APPROVED` 且 embedding 非空的 chunk。
3. 创建或校验 collection，要求维度为 `2048`、距离为 `Cosine`。
4. 删除 Qdrant 中 MySQL 已不存在的 point。
5. 全量 upsert 当前有效 point，然后启用 Qdrant 主检索。

运行中同步比较新旧不可变快照，只 upsert 新增或更新 point，并删除失效 point。如果一次 Qdrant 同步或搜索失败，`qdrantReady` 会变为 `false`，检索后端切换为 `memory`；下一次同步不再只应用差异，而是重新全量对账。

## 配置

```dotenv
QDRANT_ENABLED=true
QDRANT_HOST_PORT=6333
QDRANT_API_KEY=
QDRANT_COLLECTION=feisheng_knowledge
QDRANT_VECTOR_SIZE=2048
QDRANT_BATCH_SIZE=64
QDRANT_CONNECT_TIMEOUT_SECONDS=2
QDRANT_READ_TIMEOUT_SECONDS=10
```

Compose 内应用固定使用 `http://qdrant:6333`。`QDRANT_HOST_PORT` 只控制宿主机端口，默认仅绑定 `127.0.0.1:6333`。连接远程受保护实例时可设置 `QDRANT_URL` 和 `QDRANT_API_KEY`；当前本地容器未启用 API key 鉴权。

Qdrant Dashboard：

```text
http://localhost:6333/dashboard
```

## 管理接口

以下接口需要管理员 JWT：

```text
GET  /api/admin/rag/qdrant/status
POST /api/admin/rag/qdrant/reindex
GET  /api/admin/rag/sync-status
POST /api/admin/rag/sync
```

`qdrant/status` 实时读取 collection 状态、point 数、维度和距离。`sync-status` 返回当前 `searchBackend`、`qdrantReady`、内存向量数，以及最近同步的 Qdrant 增删数量和错误。

手动全量重建：

```powershell
$headers = @{ Authorization = "Bearer <admin-token>" }
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8082/api/admin/rag/qdrant/reindex `
  -Headers $headers
```

## 部署

```powershell
docker compose up -d qdrant
docker compose build app
docker compose up -d --no-build app
docker compose ps app qdrant
```

查看启动回填日志：

```powershell
docker compose logs --tail 100 app
```

正常日志应包含 `qdrantSynced=true`。首次启动的 `qdrantUpserted` 等于有效向量总数；无数据变化时后续同步为 `qdrantUpserted=0`、`qdrantDeleted=0`。

## 验收结果

第五阶段已于 2026-07-15 在真实 Docker 环境完成验收：

- Qdrant `v1.14.1` 和应用容器健康。
- collection `feisheng_knowledge` 状态为 `green`，维度 `2048`，距离 `Cosine`。
- 从 MySQL 全量回填 `127` 个已审核文档 chunk，FAQ 向量为 `0`。
- `indexed_vectors_count=0` 是小 collection 未超过 Qdrant HNSW 建索引阈值的正常行为，检索仍由 Qdrant 执行精确扫描。
- 真实问题“套餐价格、有效期和续费怎么查询？”通过 Qdrant 返回 5 个候选，最高分 `0.687`，最终为 `rag_ai` 回答并返回 3 条引用。
- 停止 Qdrant 后，同一问题继续返回 `200`、`rag_ai` 和 3 条引用，`searchBackend` 自动变为 `memory`。
- Qdrant 恢复后，管理接口全量重建 `127` 个 point，`searchBackend` 恢复为 `qdrant`。
- 人工删除 collection 后，下一次普通增量同步检测到 point 数不一致，当次自动重建并回填 `127` 个 point。
- knowledge 模块 12 个测试通过；完整 reactor 共 48 个测试，0 失败、0 错误。
