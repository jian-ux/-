# 分层上下文管理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement plan task-by-task.

**Goal:** 在现有客服对话链路中落地规则安全层、上下文候选筛选、小模型/大模型级联决策、任务状态和跨会话记忆召回，确保本轮明确要求不丢失且所有上下文选择可追溯。

**Architecture:** 以不可变 `TurnContext` 作为单轮协议，先由确定性规则完成安全与预算校验，再由 `ContextCandidateSelector` 汇总当前会话、跨会话摘要和长期记忆候选。`FastContextClassifier` 使用现有结构化意图服务作为首个适配器，高置信度直接通过；低置信度或复杂场景交给 `DeepContextResolver`，统一由校验器决定接受、澄清或保守降级。旧 `ContextualQueryResolver` 保留为模型不可用时的兜底。

**Tech Stack:** Java 17, Spring Boot 3.2, MyBatis-Plus, Jackson, JUnit 5, Mockito, Maven。

**Spec:** `docs/superpowers/specs/2026-09-01-layered-context-management-design.md`

## Global Constraints

- `originalQuery` 永远保留，任何上下文补全只能生成新的 `resolvedQuery`。
- 客户跨会话数据必须按 `channelType + channelUserId`（及现有客户 ID）隔离；`playground` 不参与客户级召回。
- 任意模型只能接收筛选后的候选，不能接收无限历史。
- 规则不得以关键词直接裁决追问关系或覆盖本轮明确要求。
- 模型失败、超时或输出非法时必须保守降级并记录原因。
- 不回退或覆盖工作区已有未提交改动。

### Task 1: 建立统一上下文协议和候选筛选器

**Files:**
- Create: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/TurnContext.java`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/ContextCandidate.java`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/ContextCandidateSelector.java`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/ContextCandidateSelectorTest.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/CustomerContextRecallService.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/CustomerContextSnapshot.java`

**Interfaces:**
- `ContextCandidateSelector.select(String channelType, String channelUserId, Long conversationId, String originalQuery, ConversationStateService.Snapshot state, List<BotMessage> recent, CustomerContextSnapshot customerContext, int maxCandidates)` returns `List<ContextCandidate>`.
- `TurnContext.start(String turnId, String channelType, String channelUserId, Long conversationId, Long messageId, String originalQuery, List<ContextCandidate> candidates)` creates an immutable request envelope.
- Each `ContextCandidate` contains `contextId`, `sourceType`, `content`, `sessionId`, `messageId`, `confidence`, `createdAt`, `expiresAt`, and `reason`.

- [ ] **Step 1: Write failing tests** for priority ordering, playground exclusion, customer isolation metadata, expired-memory filtering, de-duplication, and hard candidate cap.
- [ ] **Step 2: Run `mvn -pl feisheng-bot-core -am -Dtest=ContextCandidateSelectorTest test` and verify failure because the selector/protocol does not exist.**
- [ ] **Step 3: Implement immutable records and selector using existing `CustomerContextSnapshot`, recent messages, task state, and bounded customer history/long-term sections.** Preserve source IDs where available; do not infer semantic relation.
- [ ] **Step 4: Run the focused test and existing `CustomerContextRecallServiceTest`/`ConversationContextAssemblerTest`; verify all pass.**
- [ ] **Step 5: Commit with `git add` limited to Task 1 files and `git commit -m "feat: add bounded context candidate selection"`.**

### Task 2: Add layered decision protocol and model routing

**Files:**
- Create: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/ContextDecision.java`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/DecisionValidator.java`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/LayeredContextDecisionService.java`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/LayeredContextDecisionServiceTest.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/IntentUnderstandingService.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/resources/application.yml`

**Interfaces:**
- `LayeredContextDecisionService.decide(TurnContext context, Long fastModelId, Long deepModelId)` returns `DecisionResult` with route, validated decision, and fallback reason.
- `ContextDecision` fields: `relation`, `intent`, `selectedContextIds`, `selectedMemoryIds`, `taskAction`, `taskId`, `originalRequirements`, `resolvedQuery`, `confidence`, and `needLargeModel`.
- `DecisionValidator.validate(TurnContext context, ContextDecision decision)` rejects unknown IDs, cross-customer candidates, invalid fields, missing resolved query, and out-of-range confidence.

- [ ] **Step 1: Write failing tests** for high-confidence fast-model acceptance, low-confidence deep-model escalation, complex/multi-task escalation, invalid selected IDs, preservation of original requirements, and conservative model failure fallback.
- [ ] **Step 2: Run `mvn -pl feisheng-bot-core -am -Dtest=LayeredContextDecisionServiceTest test` and verify failure before implementation.**
- [ ] **Step 3: Implement the protocol, validator, and routing service.** Adapt existing `IntentUnderstandingService.Understanding` to `ContextDecision`; use configuration for thresholds and keep `ContextualQueryResolver` only as fallback.
- [ ] **Step 4: Run focused tests and existing `IntentUnderstandingServiceTest`, `DialogServiceImplTest`, and `ConversationStateServiceTest`.** Fix regressions before proceeding.
- [ ] **Step 5: Commit only Task 2 files with `git commit -m "feat: add layered context decision routing"`.**

### Task 3: Introduce task collection compatibility for conversation state

**Files:**
- Create: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/ConversationTaskManager.java`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/ConversationTaskManagerTest.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/ConversationStateService.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/impl/DialogServiceImpl.java`

**Interfaces:**
- `ConversationTaskManager.apply(Long conversationId, ContextDecision decision, ConversationStateService.Snapshot legacyState)` returns `TaskSnapshot`.
- `TaskSnapshot` exposes active task, all task states, selected task ID, and serialized legacy-compatible state.
- Status values: `ACTIVE`, `WAITING_FOR_USER`, `PAUSED`, `RESOLVED`, `CANCELLED`.

- [ ] **Step 1: Write failing tests** for new-topic creation, follow-up continuation, pause/resume by selected task ID, waiting-for-user preservation, resolved task closure, and legacy state read compatibility.
- [ ] **Step 2: Run the focused task-manager test and verify it fails because task collection behavior is absent.**
- [ ] **Step 3: Implement task collection as a bounded JSON extension of existing `dialog_state`; preserve old fields and fallback parsing.**
- [ ] **Step 4: Wire `DialogServiceImpl` to apply the validated decision once per turn and pass the selected task slots to retrieval.**
- [ ] **Step 5: Run all core service tests and commit Task 3 files with `git commit -m "feat: manage multi-topic conversation tasks"`.**

### Task 4: Expand cross-session memory metadata, retrieval input, and multi-turn verification

**Files:**
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/entity/BotCustomerMemory.java`
- Modify: `feisheng-bot-parent/sql/43_add_customer_long_term_context.sql`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/CustomerLongTermMemoryService.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/CustomerConversationHistoryService.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/ConversationContextAssembler.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/impl/DialogServiceImpl.java`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/LayeredContextMultiTurnTest.java`

**Interfaces:**
- Long-term memory records add nullable `memoryType`, `expiresAt`, `confirmationStatus`, and `supersededBy` while preserving existing rows.
- `CustomerConversationHistoryService.contextFor` remains customer-scoped and bounded; selector assigns IDs and reasons without changing knowledge-base retrieval isolation.
- Retrieval receives `originalQuery`, `resolvedQuery`, `originalRequirements`, task slots, and selected source IDs in metadata.

- [ ] **Step 1: Write failing integration-style tests** for: “点签的使用教程” → “有没有视频的？”, new-topic reset, cross-session relevant memory, unrelated memory exclusion, customer isolation, model timeout fallback, correction, and task resume.
- [ ] **Step 2: Run the focused multi-turn test and verify failures identify missing layered behavior rather than test setup errors.**
- [ ] **Step 3: Implement additive memory metadata migration, bounded cross-session candidate rendering, and retrieval metadata propagation.** Do not index customer memory as knowledge-base evidence.
- [ ] **Step 4: Run the full core module test suite: `mvn -pl feisheng-bot-core -am test`.**
- [ ] **Step 5: Review `git diff --check`, inspect route/fallback diagnostics, and commit only implementation files after confirming unrelated user changes remain untouched.**

### Task 5: Run customer-perspective black-box conversation acceptance tests

**Files:**
- Create: `docs/evaluation-results/layered-context-customer-acceptance-2026-09-01.md`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/LayeredContextMultiTurnTest.java`

**Interfaces:**
- Use the public dialog service/API path rather than directly constructing `ContextDecision` objects.
- Each result records customer identity, session boundary, customer messages, observed response/route, pass/fail, and diagnostic reason without storing unredacted sensitive data.

- [ ] **Step 1: Build a black-box scenario matrix** covering tutorial-format follow-up, new-topic reset, correction, concurrent task pause/resume, relevant cross-session recall, irrelevant-memory rejection, cross-customer isolation, and model-unavailable fallback.
- [ ] **Step 2: Start the local service against an isolated test profile/database or use the closest available public service facade with deterministic model/retrieval doubles.** Verify that the test calls the same request pipeline used by customers.
- [ ] **Step 3: Execute every scenario as sequential customer messages** and capture the observed answer, selected context route, fallback reason, and assertion result.
- [ ] **Step 4: Convert each failed black-box scenario into a regression assertion in `LayeredContextMultiTurnTest`, fix only failures caused by the new implementation, then rerun the entire matrix.**
- [ ] **Step 5: Write the acceptance report with passed capabilities, failed capabilities, infrastructure limits, and remaining risks.** Do not call an unexecuted scenario successful.

## Self-Review Checklist

- [x] Every requirement in the design spec has a corresponding task: protocol, selector, model cascade, validation, task collection, memory metadata, isolation, fallback, metrics/tests.
- [x] No task relies on an undefined type or method; interfaces are declared before consumers.
- [x] The plan does not require unlimited history or immediate model training.
- [x] Existing uncommitted work is explicitly preserved.
- [x] Each task has a failing-test step before production implementation and a final customer-perspective acceptance phase.
