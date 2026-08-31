# 对话链路优化实施计划

> **供执行代理使用：** 必须按任务逐项执行本计划。推荐使用 `superpowers:subagent-driven-development`，也可以使用 `superpowers:executing-plans`。每个任务都要完成独立测试，并保持小步提交。

**目标：** 在不破坏现有上下文、客户记忆、RAG、转人工和回复协议的前提下，降低智能客服的响应延迟和尾部失败率。

**架构：** 保留 `DialogServiceImpl` 作为请求总编排器，同时把上下文召回和检索协调拆成有界、可测试的组件。请求链路只处理当前回答必需的数据；摘要、AI 画像提取和 OCR 记忆通过幂等 Outbox 后台写入。客户级记忆继续与知识库事实隔离。

**技术栈：** Java 17、Spring Boot 3、MyBatis-Plus、RedisUtil、Resilience4j、JUnit 5、Mockito、Docker Compose。

**依据：** 用户提供的优化方案截图，以及 `feisheng-bot-parent/feisheng-bot-core` 当前对话链路实现。

## 全局约束

- 保留 `DialogServiceImpl.send*` 的所有公开方法签名和现有响应字段；新增字段只能向后兼容地追加。
- 客户身份仍按 `channelType + channelUserId` 绑定；本次不引入跨渠道身份合并。
- 不增加只依赖关键词的新意图路由；历史回顾继续使用语义理解和已保存上下文。
- 当前消息、客户画像、长期记忆、客户历史和知识库 RAG 必须保持独立且有明确标签。
- 首次回复不能等待摘要、AI 画像提取或 OCR Outbox 任务完成。
- 所有线程池必须有界、有监控，并定义队列饱和时的降级行为。
- Outbox 消费必须幂等、可重试；重复事件不能重复写入记忆或摘要。
- 保持现有脱敏行为和 `redactedTypes` 监控信息。
- 保留环境变量覆盖能力，禁止提交密钥。

---

### 任务 1：建立耗时和失败协议

**文件：**
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/DialogErrorCode.java`
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/DialogFailure.java`
- 修改：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/impl/DialogServiceImpl.java`
- 修改：`feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/impl/DialogServiceImplTest.java`
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/DialogFailureTest.java`

**接口：**
- `DialogErrorCode` 提供稳定错误码，例如 `INPUT_INVALID`、`RETRIEVAL_UNAVAILABLE`、`MODEL_TIMEOUT`、`MODEL_CIRCUIT_OPEN`、`ASYNC_QUEUE_FULL` 和 `INTERNAL_ERROR`。
- `DialogFailure` 携带 `DialogErrorCode code`、面向用户的安全提示和可选原因；不得序列化密钥或供应商凭据。
- `DialogServiceImpl` 保持现有 `reply`/`answerStatus` 字段，仅在失败路径追加 `errorCode`。

- [x] **步骤 1：编写失败测试**，覆盖模型超时、熔断打开、检索失败和队列饱和，确认用户回复不变，只新增诊断错误码。
- [x] **步骤 2：运行定向测试**

  ```powershell
  mvn -pl feisheng-bot-parent/feisheng-bot-core -Dtest=DialogServiceImplTest,DialogFailureTest test
  ```

  预期：新测试先失败，因为尚未实现统一错误映射。
- [x] **步骤 3：实现错误码、失败对象和 `DialogServiceImpl` 中的映射辅助方法，保留现有兜底文案。**
- [x] **步骤 4：重新运行定向 Maven 测试并确认通过。**
- [x] **步骤 5：仅提交上述文件，提交信息为 `refactor: structure dialog failure diagnostics`。**

### 任务 2：并行召回客户上下文并增加有界检索缓存

**文件：**
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/CustomerContextSnapshot.java`
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/CustomerContextRecallService.java`
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/config/DialogExecutorConfig.java`
- 修改：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/impl/DialogServiceImpl.java`
- 修改：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/impl/RagRetrievalService.java`
- 修改：`feisheng-bot-parent/feisheng-bot-core/src/main/resources/application.yml`
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/CustomerContextRecallServiceTest.java`
- 修改：`feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/impl/RagRetrievalServiceTest.java`

**接口：**
- `CustomerContextRecallService.recall(String channelType, String channelUserId, Long conversationId, String question, ConversationStateService.Snapshot state, List<BotMessage> recent)` 返回不可变的 `CustomerContextSnapshot`，包含画像、长期摘要、长期事实、历史片段和各来源诊断信息。
- `DialogExecutorConfig` 提供可配置核心线程数、最大线程数和队列容量的有界线程池。
- `RagRetrievalService` 的缓存键必须包含规范化查询、知识集/版本、检索过滤条件和模型/重排配置；缓存未命中时保留现有融合检索兜底。

- [x] **步骤 1：编写失败测试**，证明画像、记忆和历史加载可以并发执行；单个加载失败只影响对应区块；线程池饱和时返回空区块而不是无限等待。
- [x] **步骤 2：运行 `mvn -pl feisheng-bot-parent/feisheng-bot-core -Dtest=CustomerContextRecallServiceTest test`，确认测试失败。**
- [x] **步骤 3：实现 `CustomerContextSnapshot` 和有界线程池，使用 `CompletableFuture`、统一截止时间以及 `allOf`/逐任务降级。** 查询改写依赖检索输入，因此不能与检索并行。
- [x] **步骤 4：用召回服务替换 `processInternal` 中画像/记忆/历史的顺序调用，并保留 `ConversationContextAssembler` 的现有标签。**
- [x] **步骤 5：为成功且证据安全的检索结果增加短 TTL Redis 缓存，记录命中、未命中、过期和兜底诊断。** 客户上下文不得写入知识库缓存。
- [x] **步骤 6：运行定向服务测试和现有对话测试，提交信息为 `perf: parallelize customer context recall`。**

### 任务 3：异步化上下文压缩、画像提取和 OCR 更新

**文件：**
- 新建：`feisheng-bot-parent/sql/44_add_dialog_memory_outbox.sql`
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/entity/BotMemoryOutboxEvent.java`
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/mapper/BotMemoryOutboxEventMapper.java`
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/CustomerMemoryOutboxService.java`
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/CustomerMemoryOutboxWorker.java`
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/config/CustomerMemoryOutboxExecutorConfig.java`
- 修改：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/impl/DialogServiceImpl.java`
- 修改：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/CustomerProfileService.java`
- 修改：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/CustomerMediaMemoryService.java`
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/CustomerMemoryOutboxServiceTest.java`
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/CustomerMemoryOutboxWorkerTest.java`

**接口：**
- `CustomerMemoryOutboxService.enqueue(String eventType, String dedupKey, Long customerId, Long conversationId, Long sourceMessageId, String payload)` 使用唯一去重键插入一条待处理事件。
- 事件类型为 `CONTEXT_SUMMARY`、`PROFILE_AI_EXTRACTION`、`CUSTOMER_LONG_TERM_SUMMARY` 和 `MEDIA_OCR_MEMORY`。
- `CustomerMemoryOutboxWorker.processBatch(int limit)` 领取待处理行，按退避策略重试临时错误，并在永久失败时记录失败，不得中断聊天请求。

- [ ] **步骤 1：编写失败测试**，覆盖去重、领取/租约过期、重试退避和幂等处理。
- [ ] **步骤 2：增加 SQL 表**，包含 `status`、`attempts`、`available_at`、`locked_until`、`dedup_key` 字段及待处理索引，并通过迁移文本存储契约测试。
- [ ] **步骤 3：实现 Outbox 服务和有界调度器，记录队列深度、等待时长、重试次数和失败码。**
- [ ] **步骤 4：修改 `maybeCompressConversation`，请求路径只入队 `CONTEXT_SUMMARY` 并继续使用旧摘要；客户明确事实的确定性纠正保持同步，模型提取和长期摘要入队。**
- [ ] **步骤 5：消息持久化后入队 OCR 任务；`bot_customer_media` 继续作为不可信记忆，不能自动参与知识库 RAG。**
- [ ] **步骤 6：运行定向测试和双消息集成测试，证明首条回复不等待后台任务，提交信息为 `perf: move customer memory updates to outbox`。**

### 任务 4：脱敏和响应元数据去重

**文件：**
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/RedactionMemoizer.java`
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/DialogResponseMetadata.java`
- 修改：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/impl/DialogServiceImpl.java`
- 修改：`feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/impl/DialogServiceImplTest.java`
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/RedactionMemoizerTest.java`

**接口：**
- `RedactionMemoizer.redact(String value, Set<String> redactedTypes)` 仅在单次请求内缓存结果，并保持原脱敏语义。
- `DialogResponseMetadata` 只构建一次公共诊断 Map；响应、消息元数据和 AI 回复日志复用同一组值，不改变已有 JSON 键。

- [ ] **步骤 1：编写失败测试**，覆盖重复值脱敏、脱敏类型累积、请求间无缓存泄漏，以及现有响应键的兼容性。
- [ ] **步骤 2：实现请求级缓存和元数据构建器，先替换重复度最高的调用点。**
- [ ] **步骤 3：运行全部 `DialogServiceImplTest`，确认敏感值不会出现在响应、元数据或日志中。**
- [ ] **步骤 4：提交信息为 `perf: deduplicate dialog redaction and metadata`。**

### 任务 5：渐进式拆分 `processInternal` 流程

**文件：**
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/DialogContextLoader.java`
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/DialogRetrievalCoordinator.java`
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/DialogResponsePostProcessor.java`
- 修改：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/impl/DialogServiceImpl.java`
- 修改：`feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/impl/DialogServiceImplTest.java`

**接口：**
- `DialogContextLoader.load(...)` 返回 Prompt 组装所需的不可变请求上下文。
- `DialogRetrievalCoordinator.retrieve(...)` 负责查询变体、RAG 调用、缓存和检索诊断。
- `DialogResponsePostProcessor.process(...)` 负责模型信号解析、证据防护、安全后检、格式化、附件和响应元数据。

- [ ] **步骤 1：用定向测试固定现有行为**，覆盖问候、历史回顾、直接 FAQ、RAG 答案、无答案、证据不完整、安全拦截和转人工。
- [ ] **步骤 2：抽取上下文加载逻辑，不改变分支顺序和响应值，然后运行行为测试。**
- [ ] **步骤 3：抽取检索协调逻辑，保持 `RetrievalResult` 和诊断协议，然后运行检索及对话测试。**
- [ ] **步骤 4：抽取回复后处理逻辑，保持 `saveReplyLog` 入参不变，然后运行 core 完整测试集。**
- [ ] **步骤 5：每次拆分单独提交，便于定位回归；最终提交信息为 `refactor: split dialog pipeline stages`。**

### 任务 6：配置、韧性调优和发布验证

**文件：**
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/config/DialogPerformanceProperties.java`
- 修改：`feisheng-bot-parent/feisheng-bot-core/src/main/resources/application.yml`
- 修改：`feisheng-bot-parent/feisheng-bot-admin/src/main/resources/application.yml`
- 修改：`.env.example`
- 修改：`docker-compose.yml`
- 新建：`scripts/benchmark-dialog-pipeline.ps1`
- 新建：`feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/DialogPerformancePropertiesTest.java`

**接口：**
- `DialogPerformanceProperties` 暴露线程池大小、召回截止时间、Outbox 批大小、缓存 TTL、模型连接/读取超时和熔断阈值，并校验默认值。
- `scripts/benchmark-dialog-pipeline.ps1` 使用固定样本记录 p50/p95/p99 总延迟、召回延迟、检索延迟、模型延迟、缓存命中率、兜底率和 Outbox 延迟。

- [ ] **步骤 1：增加配置绑定测试**，拒绝零值/负值线程池参数，并保持当前生产安全的超时默认值。
- [ ] **步骤 2：按模型供应商配置超时和 CircuitBreaker；保留 `AiModelServiceImpl` 当前降级顺序，并暴露熔断打开/超时诊断。**
- [ ] **步骤 3：只把运行参数和已有配置驱动的客服文案移入集中配置；协议标记和安全不变量仍保留在代码中。**
- [ ] **步骤 4：运行单元测试、构建 core/admin 模块，并在每次性能改动前后执行基准脚本。**
- [ ] **步骤 5：重建受影响的 Docker 服务，执行健康检查和对话冒烟测试，记录回滚步骤及剩余风险。**
- [ ] **步骤 6：提交配置和验证改动，提交信息为 `chore: tune dialog pipeline operations`。**

## 验收标准

- 在固定基准样本上 p50 和 p95 端到端延迟下降，同时无答案、转人工和安全拦截比例不出现回归。
- 首条回复不依赖 Outbox 任务完成；画像、摘要和 OCR 的最终一致性可观测。
- 检索缓存按知识版本隔离，客户记忆不会混入知识事实。
- 任一加载器、模型或后台任务失败时，都返回稳定错误码和现有用户安全兜底文案。
- 现有对话、上下文、画像、历史、媒体和 RAG 测试保持通过；新增并发和幂等测试通过。
- Docker 健康检查通过，基准输出随发布改动归档。
