# 分层上下文管理设计（规则 + 小模型 + 大模型）

## 1. 目标与非目标

### 目标

解决多轮客服对话中的三个核心问题：

1. 当前问题的明确要求（例如“视频教程”）不能被历史问题覆盖。
2. 新话题、追问、纠正、补充和恢复旧任务可以被稳定区分。
3. 在同一客户范围内召回当前会话、跨会话摘要和长期记忆，并且每次召回都可追溯、可限额、可降级。

最终采用级联处理：规则安全层 → 上下文候选筛选器 → 小模型 → 大模型兜底 → 结果校验 → 任务与业务执行。

### 非目标

- V1 不把全部聊天历史直接发送给任何模型。
- V1 不直接建设泛化 Memory Graph。
- V1 不让关键词规则决定 `contextDependent`、历史关联关系或最终检索问句。
- V1 不立即要求训练专用小模型；小模型通过稳定接口接入，初期可使用已有低成本模型，标注数据足够后再替换为微调模型。

## 2. 当前实现基线

当前核心能力已经存在，但职责分散：

- `DialogServiceImpl` 串联安全、状态、上下文、检索和回答。
- `ContextualQueryResolver` 通过规则识别上下文依赖并改写检索问题。
- `IntentUnderstandingService` 能让模型输出严格 JSON，但调用受规则门控，默认不是每轮主路径。
- `ConversationStateService` 将状态持久化为单一活动意图，当前窗口默认约 4 轮。
- `ConversationContextAssembler` 已分区渲染最近消息、会话摘要、用户画像、客户摘要、客户记忆和跨会话历史。
- `CustomerContextRecallService` 已并行读取客户画像、长期记忆和历史，并有召回截止时间。
- `CustomerLongTermMemoryService` 当前保存客户级显式事实；记录已有来源消息和置信度，但类型和有效期仍有限。

因此本设计采用“新增统一协议 + 适配现有服务”的迁移方式，不一次性重写 `DialogServiceImpl`。

## 3. 总体流程

```text
originalQuery
  -> RuleGuard
  -> ContextCandidateSelector
  -> FastContextClassifier
       high confidence and no conflict -> DecisionValidator
       low confidence or complex       -> DeepContextResolver
  -> DecisionValidator
  -> ConversationTaskManager
  -> RAG / API / tool execution
  -> answer generation
  -> asynchronous summary, task and memory update
```

每轮必须建立一个不可变的 `TurnContext`，贯穿筛选、判断、检索和回答。任何补全都只能生成新的 `resolvedQuery`，不能覆盖 `originalQuery`。

## 4. 分层职责

### 4.1 规则安全层（RuleGuard）

规则层只处理确定性高、边界清晰且误判代价大的事项：

- 敏感词、黑名单、身份、租户和客户隔离。
- 权限、数据脱敏、记忆有效期和 token/字符预算。
- “转人工”“结束对话”“换个问题”等显式控制指令。
- 模型输出 JSON Schema、引用 ID 和字段大小校验。
- 模型超时、不可用或输出非法时的保守降级。
- 表达完整、无指代、无活动任务冲突的固定路由特征可以直接短路，但必须记录命中原因。

规则层不得根据关键词直接认定追问关系，不得拼接最终检索问题，也不得因为“视频”“图片”等单词强制继承历史。

### 4.2 上下文候选筛选器（ContextCandidateSelector）

筛选器只召回候选，不作最终语义裁决。输入是客户身份、当前会话 ID、`originalQuery`、当前任务快照和 token 预算；输出是带来源的候选集合。

候选优先级和上限：

| 来源 | V1 上限 | 选择依据 |
| --- | ---: | --- |
| 当前活动/暂停任务 | 3 个 | 状态、更新时间、实体重合 |
| 当前会话关键消息 | 8-12 条 | 最近性、用户消息优先、当前任务相关性 |
| 当前会话摘要 | 1 条 | 最新有效摘要 |
| 同客户跨会话摘要 | 3 条 | customerId/tenantId 隔离、语义相似度、时间衰减 |
| 同客户长期记忆 | 5 条 | 实体匹配、确认状态、置信度、有效期 |
| 相似历史片段 | 3 条 | 向量/关键词混合召回，低优先级 |

每个候选必须包含：`contextId`、`sourceType`、`customerId`、`sessionId`、`messageId`（如有）、内容、创建时间、置信度、`expiresAt`（如有）和召回原因。候选在进入模型前要去重、脱敏、过滤过期或无权限记录，并执行硬 token 预算。

跨会话召回默认开启少量高相关候选，但由模型决定是否使用；筛选器不能因为召回成功就强制继承旧话题。

### 4.3 小模型层（FastContextClassifier）

小模型负责固定意图集合内的高并发判断。输入为 `originalQuery`、候选上下文、当前任务快照和规则层元数据，不接收全部历史。

输出必须符合统一 `ContextDecision` 协议，至少包括：

```json
{
  "relation": "FOLLOW_UP",
  "intent": "PRODUCT_USAGE",
  "selected_context_ids": ["turn:24:5"],
  "selected_memory_ids": [],
  "task_action": "CONTINUE",
  "task_id": "task:usage-guide",
  "original_requirements": ["需要视频形式的教程"],
  "resolved_query": "点签是否提供使用视频教程？",
  "confidence": 0.93,
  "need_large_model": false
}
```

`relation` 枚举为 `NEW_TOPIC`、`FOLLOW_UP`、`CORRECTION`、`SLOT_FILL`、`RESUME_TASK`、`HISTORY_RECALL`、`MULTI_INTENT`、`UNCERTAIN`。高置信度不等于模型自报的数字；阈值必须通过真实标注集校准。

### 4.4 大模型层（DeepContextResolver）

大模型只处理低置信度或复杂场景：多个候选任务冲突、纠正/否定、多意图、跨会话消歧、长尾表达和小模型输出与当前问题冲突。输入仍是筛选后的候选、小模型结果和原话，不接收无限历史。

大模型可以返回 `CLARIFY`，并给出澄清问题；不能在证据不足时猜测具体历史事实。

### 4.5 结果校验层（DecisionValidator）

校验包括：

- JSON Schema、枚举、长度和置信度范围。
- 所有 `selected_*_ids` 必须属于本轮候选。
- customer/tenant 标识必须一致。
- `resolvedQuery` 不能为空（除非结果要求澄清/越界）。
- `originalQuery` 和 `originalRequirements` 必须原样保留在 `TurnContext`。
- 低于阈值、冲突或非法输出进入澄清或保守降级，不自动拼接旧问题。

## 5. 核心数据协议

### 5.1 TurnContext

```text
turnId
customerId / tenantId
sessionId / currentMessageId
originalQuery
candidateContexts
candidateMemories
contextDecision
selectedContextIds / selectedMemoryIds
relation
resolvedQuery
originalRequirements
taskId / taskSlots
confidence
route (RULE / FAST_MODEL / DEEP_MODEL / CLARIFY / FALLBACK)
fallbackReason
diagnostics
```

该对象在一次请求中只读；检索、回答和审计均引用同一对象，避免各模块重新解析历史。

### 5.2 任务状态

用任务集合替代单一 Push/Pop 话题栈：

```text
ACTIVE
WAITING_FOR_USER
PAUSED
RESOLVED
CANCELLED
```

每个任务保存 `taskId`、意图、主题、槽位、来源轮次、最近更新时间和状态变更原因。新话题创建新任务；切换话题暂停旧任务；恢复历史任务由模型选择任务 ID。

### 5.3 长期记忆

V1 使用可追溯的三类记录：

- `memory_fact`：客户明确确认的稳定事实。
- `memory_event`：历史咨询、失败或已完成事件。
- `task_slot`：未完成任务的槽位。

每条记录必须包含 `customerId`、`tenantId`、来源会话/消息 ID、置信度、确认状态、创建时间、有效期和替代关系。否定、推断和一次性情绪不得成为永久事实；临时状态必须有 TTL。客户长期记忆不参与知识库事实索引，知识库与客户资料保持边界。

## 6. 检索与回答契约

RAG/API 输入必须同时携带：

```text
originalQuery
resolvedQuery
originalRequirements
taskSlots
selectedContextIds
selectedMemoryIds
```

`resolvedQuery` 用于检索和业务执行，`originalQuery` 用于回答约束和审计。例如历史为“点签的使用教程”，本轮为“有视频的吗”，不得退化成只有“点签的使用教程”；必须保留“视频形式”要求。

## 7. 现有模块迁移

| 现有模块 | V1 处理 |
| --- | --- |
| `DialogServiceImpl` | 保留编排入口，逐步改为消费 `TurnContext` |
| `ContextualQueryResolver` | 保留为模型不可用/非法输出时的保守兜底，不再主导语义 |
| `IntentUnderstandingService` | 抽象为 `FastContextClassifier` 接口；现实现可作为首个适配器 |
| 新增 `DeepContextResolver` | 复用现有大模型 JSON 调用能力，独立超时和熔断 |
| `ConversationStateService` | 兼容读取旧单状态，迁移到 `ConversationTaskManager` 任务集合 |
| `ConversationContextAssembler` | 接收筛选后的候选并保留分区标签、token 裁剪 |
| `CustomerContextRecallService` | 扩展为 `ContextCandidateSelector` 的数据源适配器 |
| `CustomerLongTermMemoryService` | 保持客户隔离，补充类型、有效期、确认和替代关系 |

迁移期间使用配置开关：`layered-context.enabled`、`fast-model.enabled`、`deep-model.enabled`、`cross-session-recall.enabled`。关闭新路径时回到现有流程，确保可回滚。

## 8. 降级与错误处理

- 规则层拦截：按现有安全回复或人工转接策略结束本轮。
- 候选召回超时：保留当前会话最近关键消息和活动任务，忽略失败来源，并记录诊断。
- 小模型超时/非法：升级大模型。
- 大模型超时/非法：不自动使用跨会话记忆；仅使用活动任务和最近关键消息，无法独立化时请求澄清。
- 模型结果引用不存在或跨客户：丢弃结果并进入澄清/保守回答。
- 任何降级都记录 `route`、`fallbackReason`、来源状态和耗时，不把错误静默成正常上下文。

## 9. 观测、评估与验收

每轮记录但不记录未脱敏原文：路由层级、候选来源/数量、选中 ID、关系、置信度、是否升级、是否澄清、检索问句哈希、耗时和 token。

离线标注集至少覆盖：

- 新话题误继承。
- 追问遗漏上下文。
- “视频教程”类本轮要求丢失。
- 纠正、否定、恢复旧任务。
- 跨会话相关与不相关记忆。
- 多客户隔离和过期记忆。

上线指标：上下文关联准确率、错误继承率、遗漏继承率、本轮要求保留率、澄清率、大模型升级率、P95 延迟、平均 token 成本和跨客户串话数（必须为 0）。

## 10. 分阶段实施

### Phase 1：统一协议与候选筛选

新增 `TurnContext`、候选模型、`ContextCandidateSelector` 和 `DecisionValidator`；复用现有画像、长期记忆、摘要和历史服务；默认 shadow mode，只记录决策不改变答案。

### Phase 2：模型级联

定义 `FastContextClassifier` 和 `DeepContextResolver`；将现有意图理解适配为小模型；高置信度直接执行，低置信度升级大模型；保留旧规则兜底。

### Phase 3：任务集合与记忆增强

把单一对话状态迁移为任务集合，补充 `memory_fact/event/task_slot`、有效期、确认和替代关系；跨会话摘要和长期记忆纳入筛选器主路径。

### Phase 4：灰度与校准

用真实会话和人工修正建立标注集，校准阈值；按客户/渠道灰度启用，比较 shadow 与线上结果；达标后逐步收缩旧规则的语义职责。

## 11. 回滚与兼容

所有新组件通过配置开关关闭；旧 `CurrentTurnRequest` 的 `originalQuestion` 与 `contextualIntent` 继续兼容。数据库新增字段/表采用向后兼容迁移，旧会话状态读取失败时回退为空闲状态。回滚不得删除已写入的记忆，恢复后由旧服务忽略未知字段。

## 12. 待评审决策

实施前需确认：

1. 小模型初期使用哪个现有模型 ID，以及是否允许按渠道配置。
2. 候选上下文总 token 上限和跨会话召回的默认开关。
3. 任务集合是否先以 JSON 扩展现有 `dialog_state`，还是直接新增任务表。
4. 真实会话标注集的抽样范围、脱敏方式和验收阈值。
