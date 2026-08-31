# Customer Long-Term Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with verification checkpoints.

**Goal:** 在不改变现有单渠道客户身份和关闭会话行为的前提下，为每个客户保留跨会话长期上下文、受控画像记忆、独立图片记忆，并提供完整历史时间线。

**Architecture:** `bot_customer` 继续以 `channel_type + channel_user_id` 作为客户身份，新增客户级摘要字段；稳定事实写入独立的 `bot_customer_memory`，图片/OCR 元数据写入独立的 `bot_customer_media`，均不进入知识库事实索引。核心上下文组装器把当前会话、客户画像、客户摘要和受控记忆分区渲染，管理端通过客户时间线接口跨会话读取消息。

**Tech Stack:** Java 17, Spring Boot 3.2, MyBatis-Plus, MySQL, Vue 3, Element Plus, Maven.

**Spec:** 本线程中用户确认的“客户级长期摘要和受控记忆、关闭会话后新会话、客户完整历史时间线、画像和长期记忆接入上下文、独立图片记忆且不参与知识库检索”方案。

## Global Constraints

- 客户身份继续使用 `channel_type + channel_user_id`，本阶段不实现跨渠道合并。
- 关闭的会话不能被复用；下一条消息必须创建新会话，既有行为保持不变。
- 只有客户明确、稳定且可长期使用的事实才能写入客户记忆；敏感信息先脱敏。
- 客户历史和图片/OCR 资料不能写入知识库事实检索索引。
- 系统消息、内部事件和人工内部备注不注入普通 AI 上下文。
- 所有生产行为改动先写失败测试并确认失败，再写最小实现。

---

### Task 1: Add Customer Long-Term Storage Schema and Entities

**Files:**
- Create: `feisheng-bot-parent/sql/43_add_customer_long_term_context.sql`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/entity/BotCustomer.java`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/entity/BotCustomerMemory.java`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/entity/BotCustomerMedia.java`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/mapper/BotCustomerMemoryMapper.java`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/mapper/BotCustomerMediaMapper.java`
- Modify: `feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/entity/BotCustomer.java`

**Interfaces:**
- `BotCustomer.longTermSummary` and `longTermSummaryUpdatedAt` persist the customer-level summary.
- `BotCustomerMemory` stores one controlled fact per customer with `memoryKey`, `memoryValue`, `source`, `confidence`, `status`, and timestamps.
- `BotCustomerMedia` stores customer-scoped image/OCR metadata and references a source message; it has no knowledge-document foreign key.

- [ ] **Step 1: Write failing entity/schema contract tests** asserting the new Java fields and SQL table/index names.
- [ ] **Step 2: Run the focused tests and confirm they fail because the fields and tables do not exist.**
- [ ] **Step 3: Add the additive MySQL migration and MyBatis-Plus entities/mappers.** Keep `bot_customer` identity and existing logical-delete columns unchanged.
- [ ] **Step 4: Run focused tests and compile the core module.**

### Task 2: Implement Controlled Customer Memory Service

**Files:**
- Create: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/CustomerLongTermMemoryService.java`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/CustomerLongTermMemoryServiceTest.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/CustomerProfileService.java`

**Interfaces:**
- `CustomerLongTermMemoryService.load(String channelType, String channelUserId)` returns a sanitized snapshot of summary and active memories.
- `updateFromCustomerMessage(String channelType, String channelUserId, String sanitizedText)` accepts only explicit stable facts and upserts them; playground remains empty.
- `contextFor(String currentQuestion, Snapshot snapshot)` returns a bounded, clearly labeled customer-memory section or `null` when irrelevant.

- [ ] **Step 1: Add tests for explicit stable facts, negations, sensitive-data redaction, playground exclusion, and unrelated-question omission.**
- [ ] **Step 2: Run `mvn -pl feisheng-bot-core -Dtest=CustomerLongTermMemoryServiceTest test` and confirm the expected missing-service failure.**
- [ ] **Step 3: Implement the service using existing profile extraction/redaction conventions and MyBatis-Plus upserts.** Do not infer facts from names, nicknames, agent messages, or knowledge results.
- [ ] **Step 4: Re-run the focused test and existing `CustomerProfileServiceTest`.**

### Task 3: Preserve Conversation Boundaries and Update Long-Term Context

**Files:**
- Create: `feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/ConversationServiceImplTest.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/impl/DialogServiceImpl.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/impl/ConversationServiceImpl.java` only if regression coverage exposes a defect.

**Interfaces:**
- Existing `getOrCreate` continues to select only `active`/`transferred` conversations.
- Each sanitized customer message updates controlled memory and contributes to the customer-level summary without changing message ownership.

- [ ] **Step 1: Add a test proving a closed conversation is not reused and an active conversation is reused.**
- [ ] **Step 2: Run the test and confirm the baseline behavior or failure.**
- [ ] **Step 3: Wire long-term memory updates after the existing sanitized user-message save and before context assembly; keep safety checks and early-return paths intact.**
- [ ] **Step 4: Add summary update tests for a customer spanning multiple conversations, then run core service tests.**

### Task 4: Extend Context Assembly with Customer Summary and Memory

**Files:**
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/ConversationContextAssembler.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/impl/DialogServiceImpl.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/ConversationContextAssemblerTest.java`

**Interfaces:**
- `assemble(...)` accepts customer summary and controlled-memory context separately from knowledge evidence.
- Rendered prompt sections are explicitly labeled `客户长期摘要` and `客户长期记忆`; they are sanitized and budgeted after mandatory current-turn context.

- [ ] **Step 1: Add failing tests for profile plus long-term memory inclusion, bounded rendering, and exclusion of system/internal messages.**
- [ ] **Step 2: Run the focused test and confirm failure against the existing assembler signature/output.**
- [ ] **Step 3: Extend the assembler and call site with minimal compatible overloads; preserve existing prompt token budgeting and `知识库事实` separation.**
- [ ] **Step 4: Run all context, profile, summary, and state tests.**

### Task 5: Add Independent Customer Image Memory

**Files:**
- Create: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/CustomerMediaMemoryService.java`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/CustomerMediaMemoryServiceTest.java`
- Modify: `feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/ConversationImageService.java` only to reuse stored message metadata when persisting customer media.
- Modify: `feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/controller/ConversationImageController.java` only if customer-media access needs a dedicated read endpoint.

**Interfaces:**
- `saveFromMessage(String channelType, String channelUserId, BotMessage message)` persists image metadata under the customer and marks OCR text as untrusted customer material.
- Playground images remain temporary `bot_knowledge_document` chat-scope records and are never copied into customer media.

- [ ] **Step 1: Add tests proving customer images are stored independently, playground images are excluded, and no knowledge mapper/index service is called.**
- [ ] **Step 2: Run the focused test and confirm the missing service/mapper failure.**
- [ ] **Step 3: Implement persistence and bounded context rendering; label OCR as untrusted material and keep it out of retrieval filters.**
- [ ] **Step 4: Run image/OCR and knowledge retrieval tests to verify isolation.**

### Task 6: Add Cross-Conversation Customer Timeline API

**Files:**
- Create: `feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/dto/CustomerTimelineItem.java`
- Modify: `feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/mapper/BotMessageMapper.java`
- Modify: `feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/controller/CustomerController.java`
- Modify: `feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/controller/CustomerControllerTest.java`

**Interfaces:**
- `GET /api/admin/customer/{id}/timeline?page=1&size=50` returns messages from all non-deleted conversations for the visible customer, ordered by `create_time ASC, id ASC`, with conversation id/title/status on each item.
- Playground customers and unknown customer ids return an empty page or 404-compatible `R` response consistent with current customer APIs.

- [ ] **Step 1: Add MockMvc/controller tests for cross-conversation ordering, page limits, and playground exclusion.**
- [ ] **Step 2: Run the admin controller test and confirm the endpoint is absent/fails.**
- [ ] **Step 3: Add the mapper query/DTO/controller endpoint with bounded page size and no knowledge-table join.**
- [ ] **Step 4: Run `CustomerControllerTest` and admin compilation.**

### Task 7: Show Customer Timeline, Summary, and Memory in Admin UI

**Files:**
- Modify: `feisheng-bot-admin-ui/src/views/customer/Detail.vue`
- Modify: `feisheng-bot-admin-ui/src/api/index.js` only if a shared API helper is needed.
- Modify: `feisheng-bot-admin-ui/src/router/index.js` only if a new child route is required (prefer the existing detail route).

**Interfaces:**
- Customer detail displays long-term summary/profile and a paged timeline spanning every conversation for that single channel identity.
- Timeline items link back to the existing conversation detail route and distinguish user, AI, human, and system messages; system/internal items are visually separated from customer context.

- [ ] **Step 1: Add the UI request/render contract to the existing customer detail test/build setup.**
- [ ] **Step 2: Run the frontend build/test command and confirm the new bindings are absent or fail.**
- [ ] **Step 3: Implement the timeline section with loading, empty, pagination, and error states, reusing existing Element Plus and display helpers.**
- [ ] **Step 4: Run the frontend build and inspect the customer detail route at desktop and narrow width.**

### Task 8: End-to-End Verification and Documentation

**Files:**
- Modify: `feisheng-bot-parent/sql/43_add_customer_long_term_context.sql` if migration validation finds compatibility issues.
- Create: `docs/customer-long-term-context.md`

- [ ] **Step 1: Run focused core and admin tests.**
- [ ] **Step 2: Run the frontend build.**
- [ ] **Step 3: Run the Maven reactor test/compile command that is feasible in the current environment and record any infrastructure-only limitation.**
- [ ] **Step 4: Document data retention, memory admission rules, context section boundaries, and image/knowledge isolation.**
- [ ] **Step 5: Review `git diff` and `git status --short`; do not claim completion until verification output is captured.**
