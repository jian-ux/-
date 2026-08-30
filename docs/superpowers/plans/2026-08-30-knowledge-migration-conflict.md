# Knowledge Migration Conflict Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Build an asynchronous, review-gated, whole-document migration workflow that converts legacy knowledge documents into structured facts, detects factual conflicts with vector recall, and atomically switches document versions without partial online activation.

**Architecture:** The admin module owns migration jobs, document snapshotting, conflict review, and release orchestration. The knowledge module owns version-filtered structured-unit indexing and shadow-index validation. A source document remains the active published version while a cloned target document and its units are processed as a draft; only a document-level gate followed by an atomic version switch can make the target searchable.

**Tech Stack:** Java 17, Spring Boot 3.2.4, MyBatis-Plus 3.5.7, MySQL/InnoDB, Redis-backed locking where available, existing embedding/LLM clients, Qdrant with memory fallback, JUnit 5/Mockito, Vue 3, Element Plus, Vite.

**Spec:** docs/superpowers/specs/2026-08-30-knowledge-migration-conflict-design.md

## Global Constraints

- The old document remains PUBLISHED during migration; failed work never changes the active version.
- The target uses the same knowledgeSetKey and is DRAFT/shadow until an atomic switch.
- Vector recall finds candidates; it never proves a conflict by itself.
- Exact normalization and field-level rules run before the structured LLM judgment.
- Unknown, malformed, or low-confidence conflict judgments are blocking review items.
- First-version migrations require explicit human document confirmation even when zero conflicts are found.
- Every target unit must be reviewed; unresolved BLOCKING or WARNING conflicts prevent switching.
- A unit being APPROVED does not make it online; index reads require an approved unit in the active published document version.
- Switching is all-or-nothing and guarded by source content hash, optimistic locking, and a knowledgeSetKey lock.
- Old document rows, chunks, evidence, and indexes remain available for the configured rollback retention period.
- Idempotency keys are based on source document, source content hash, target version, and migration step.
- Do not delete or overwrite unrelated existing worktree changes.

---

### Task 1: Add migration and conflict persistence schema

**Files:**
- Create: feisheng-bot-parent/sql/41_add_knowledge_migration_workflow.sql
- Create: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/entity/BotKnowledgeMigrationJob.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/entity/BotKnowledgeConflict.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/mapper/BotKnowledgeMigrationJobMapper.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/mapper/BotKnowledgeConflictMapper.java
- Modify: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/mapper/BotKnowledgeDocumentMapper.java
- Modify: feisheng-bot-parent/feisheng-bot-knowledge/src/main/java/com/feisheng/bot/knowledge/mapper/BotKnowledgeSemanticUnitMapper.java

**Interfaces:**
- BotKnowledgeMigrationJob contains source/target document and version IDs, knowledgeSetKey, sourceContentHash, status, currentStep, progress counters, retry/lease fields, lockVersion, reviewer and switch timestamps.
- BotKnowledgeConflict contains target/candidate unit IDs, similarity, scope relation, conflict type, severity, evidence, rule/LLM results, resolution, reviewer and timestamps.
- BotKnowledgeMigrationJobMapper.claim(Long jobId, String expectedStatus, String workerId, Date leaseUntil, long expectedLockVersion) returns the number of updated rows.
- BotKnowledgeMigrationJobMapper.transition(...) and findByIdForUpdate(Long id) provide compare-and-set state changes.
- BotKnowledgeDocumentMapper.selectPublishedByKnowledgeSetKey(String key) and guarded update methods support release.
- BotKnowledgeSemanticUnitMapper.selectIndexableApprovedUnits(...) returns only approved units in effective published documents.

- [ ] **Step 1: Write the idempotent MySQL migration.**

Create both tables with utf8mb4, InnoDB, JSON evidence/result columns, indexes on job status and knowledge set, and these uniqueness constraints:

~~~sql
UNIQUE KEY uk_migration_source_hash
  (source_document_id, source_content_hash, target_version_id),
UNIQUE KEY uk_migration_conflict_pair
  (migration_job_id, target_unit_id, candidate_unit_id)
~~~

Use information_schema checks for repeatable column/index creation, matching migrations 21 and 23. Add indexes for (knowledge_set_key, status), (status, updated_at), and (migration_job_id, status, severity).

- [ ] **Step 2: Add MyBatis entities and mapper SQL.**

Use TableName, TableId(type = IdType.AUTO), Date, and JSON fields as String, matching existing entities. Add conditional mapper transitions so a stale worker updates zero rows instead of overwriting another worker:

~~~java
int claim(@Param("id") Long id,
          @Param("expectedStatus") String expectedStatus,
          @Param("workerId") String workerId,
          @Param("leaseUntil") Date leaseUntil,
          @Param("expectedLockVersion") long expectedLockVersion);
~~~

Add selectPublishedByKnowledgeSetKey with publish_status = 'PUBLISHED', source_scope = 'KNOWLEDGE', and deleted = 0. Add the semantic-unit join query with the same publication and effective-time predicates.

- [ ] **Step 3: Verify the schema and mapper contracts.**

Run the SQL against the project MySQL service or migration test profile, then run:

~~~powershell
mvn -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin -am -DskipTests compile
~~~

Expected: migration can run twice without errors, entities compile, and no existing table data is changed except new indexes/columns.

- [ ] **Step 4: Commit the persistence foundation.**

~~~powershell
git add feisheng-bot-parent/sql/41_add_knowledge_migration_workflow.sql feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/entity feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/mapper/BotKnowledgeMigrationJobMapper.java feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/mapper/BotKnowledgeConflictMapper.java feisheng-bot-parent/feisheng-bot-knowledge/src/main/java/com/feisheng/bot/knowledge/mapper/BotKnowledgeSemanticUnitMapper.java
git commit -m "feat: persist knowledge migration jobs and conflicts"
~~~

### Task 2: Snapshot a source document into a draft target version

**Files:**
- Create: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/KnowledgeMigrationSnapshotService.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/service/KnowledgeMigrationSnapshotServiceTest.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/controller/AdminDocControllerTest.java
- Modify: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/controller/AdminDocController.java
- Modify: feisheng-bot-parent/feisheng-bot-knowledge/src/main/java/com/feisheng/bot/knowledge/controller/DocumentController.java

**Interfaces:**
- KnowledgeMigrationSnapshotService.create(Long sourceDocumentId, Long operatorId) returns SnapshotResult(jobId, sourceDocumentId, targetDocumentId, targetVersion, sourceContentHash, chunkCount).
- KnowledgeMigrationSnapshotService.cloneTarget(Long jobId) is idempotent and creates one target document and cloned chunks.
- The service consumes KnowledgeDocumentReleaseService.nextVersion(String key), both document/chunk mappers, and the job mapper.

- [ ] **Step 1: Write snapshot tests before implementation.**

Cover missing/incomplete/non-knowledge sources, duplicate source hash returning the existing active job, next target version and DRAFT publish status, cloned chunk content/section/status/embedding metadata with new IDs, unchanged source rows, and source hash invalidation after a source edit.

- [ ] **Step 2: Implement source hashing and target cloning.**

Hash sorted source chunk IDs, indexes, and content with the same UTF-8 SHA-256 convention used by StructuredKnowledgeExtractionService. Copy document metadata and the shared MinIO bucketName/objectKey without copying the binary. Set target status = 2, publishStatus = DRAFT, knowledgeSetKey unchanged, documentVersion = nextVersion(key), and supersedesDocumentId = source. Clone chunks in index order so evidence validation remains stable.

- [ ] **Step 3: Make shared-object deletion safe.**

Before deleting a document object in both controllers, count other non-deleted documents with the same bucketName and objectKey. Delete the MinIO object only when the count is zero; always delete only the requested document/chunks. Add a regression test that deleting a draft target cannot remove the source object's storage.

- [ ] **Step 4: Run the focused test and commit.**

~~~powershell
mvn -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin -am -Dtest=KnowledgeMigrationSnapshotServiceTest,AdminDocControllerTest test
git add feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/KnowledgeMigrationSnapshotService.java feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/service/KnowledgeMigrationSnapshotServiceTest.java feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/controller/AdminDocController.java feisheng-bot-parent/feisheng-bot-knowledge/src/main/java/com/feisheng/bot/knowledge/controller/DocumentController.java
git commit -m "feat: snapshot knowledge documents for migration"
~~~

### Task 3: Normalize structured facts and compare factual fields

**Files:**
- Create: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/FactNormalizationService.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/FactComparisonService.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/service/FactNormalizationServiceTest.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/service/FactComparisonServiceTest.java

**Interfaces:**
- FactNormalizationService.normalize(BotKnowledgeSemanticUnit unit) returns immutable NormalizedFact with normalized question, statement, polarity, numeric values/ranges, temporal values, enum values, process steps, scope fields, and original evidence IDs.
- FactComparisonService.compare(NormalizedFact left, NormalizedFact right) returns ComparisonResult(relation, conflictType, severity, differingFields, explanation), with relation CONFLICT, NOT_CONFLICT, SCOPE_DIFFERENCE, or UNKNOWN.

- [ ] **Step 1: Define normalization fixtures and failing tests.**

Test full/half-width punctuation, whitespace, Chinese/Arabic numerals, RMB and percentage units, date formats with timezone, 7天 versus 168小时, open/closed numeric ranges, polarity words (可以/不可以, 必须/无需), and scope JSON fields. Assert equivalent values normalize identically.

- [ ] **Step 2: Implement deterministic normalization.**

Use Jackson for entitiesJson, conditionsJson, exclusionsJson, and metadataJson; BigDecimal for numeric comparisons; java.time with Asia/Shanghai as the default zone when no offset is present. Preserve original strings and evidence separately. Do not infer missing scope as global; represent it as UNKNOWN.

- [ ] **Step 3: Implement field-level comparison and scope relation.**

Compare polarity, amounts, quantities, rates, dates, durations, limits, enums, process steps, and scope. Return SCOPE_DIFFERENCE for mutually exclusive scopes, CONFLICT only when overlapping scopes contain incompatible conclusions, and UNKNOWN when scope or values cannot be compared. Severity is BLOCKING for eligibility, amounts, deadlines, mandatory materials, polarity, and process order; WARNING for unresolved wording or merge choices; INFO for duplicates.

- [ ] **Step 4: Verify and commit the pure logic.**

~~~powershell
mvn -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin -am -Dtest=FactNormalizationServiceTest,FactComparisonServiceTest test
git add feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/FactNormalizationService.java feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/FactComparisonService.java feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/service/FactNormalizationServiceTest.java feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/service/FactComparisonServiceTest.java
git commit -m "feat: compare normalized knowledge facts"
~~~

### Task 4: Add published-only vector candidate recall and conflict evaluation

**Files:**
- Modify: feisheng-bot-parent/feisheng-bot-knowledge/src/main/java/com/feisheng/bot/knowledge/service/StructuredKnowledgeUnitIndexService.java
- Modify: feisheng-bot-parent/feisheng-bot-knowledge/src/test/java/com/feisheng/bot/knowledge/service/StructuredKnowledgeUnitIndexServiceTest.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/FactConflictService.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/service/FactConflictServiceTest.java

**Interfaces:**
- StructuredKnowledgeUnitIndexService.searchConflictCandidates(ConflictQuery query) returns List<ConflictCandidate> from approved units in effective published documents only. ConflictQuery contains query vector, knowledgeSetKey, excluded target document ID, topK, and calibrated minScore.
- FactConflictService.check(Long migrationJobId, Long sourceDocumentId, Long targetDocumentId) returns ConflictReport(totalTargetUnits, candidatePairs, blocking, warning, info, unknown) and upserts BotKnowledgeConflict rows idempotently.

- [ ] **Step 1: Add index tests for the activation gate.**

Build snapshots containing approved units in PUBLISHED, DRAFT, and ARCHIVED documents. Assert only effective published units load and search. Assert an approved target unit is absent until its document is published. Assert Qdrant hits are rechecked against the authoritative memory snapshot.

- [ ] **Step 2: Implement a dedicated conflict-recall API.**

Keep public search filters restricted to trusted user fields. For conflict recall, apply mandatory server-side filters for the same knowledgeSetKey, effective date, product/channel/audience scope, and non-target document. Use Qdrant when ready and memory cosine fallback otherwise; return similarity and authoritative unit fields. Keep topK = 20 as the initial default and make the threshold configuration-driven, separate from QuestionClusteringService's 0.82 clustering threshold.

- [ ] **Step 3: Implement conflict evaluation and idempotent persistence.**

For each target unit with a valid vector, call recall, normalize both facts, compare fields, and persist source/target evidence, model metadata, similarity, type, severity, relation, and judgment JSON. Create no duplicate row for a repeated job/target/candidate pair. Treat missing target vectors, malformed evidence, unknown scope, and LLM parse failures as blocking review items.

- [ ] **Step 4: Verify candidate recall and conflict persistence.**

~~~powershell
mvn -f feisheng-bot-parent/pom.xml -pl feisheng-bot-knowledge -am -Dtest=StructuredKnowledgeUnitIndexServiceTest test
mvn -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin -am -Dtest=FactConflictServiceTest test
git add feisheng-bot-parent/feisheng-bot-knowledge/src/main/java/com/feisheng/bot/knowledge/service/StructuredKnowledgeUnitIndexService.java feisheng-bot-parent/feisheng-bot-knowledge/src/test/java/com/feisheng/bot/knowledge/service/StructuredKnowledgeUnitIndexServiceTest.java feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/FactConflictService.java feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/service/FactConflictServiceTest.java
git commit -m "feat: recall and persist knowledge fact conflicts"
~~~

### Task 5: Implement the asynchronous migration state machine

**Files:**
- Create: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/KnowledgeMigrationStatus.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/KnowledgeMigrationJobService.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/KnowledgeMigrationWorker.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/config/KnowledgeMigrationExecutorConfig.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/service/KnowledgeMigrationJobServiceTest.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/service/KnowledgeMigrationWorkerTest.java
- Modify: feisheng-bot-parent/feisheng-bot-admin/src/main/resources/application.yml

**Interfaces:**
- KnowledgeMigrationJobService.create(Long sourceDocumentId, Long operatorId) returns MigrationJobView and enqueues the job.
- KnowledgeMigrationJobService.get(Long jobId) returns current status, progress, blocking counts, and last error.
- KnowledgeMigrationJobService.retry(Long jobId, Long operatorId) resets only failed steps and re-enqueues.
- KnowledgeMigrationWorker.run(Long jobId) is idempotent and advances exactly one claimed job through extraction, embedding, conflict checking, and review-required state.

- [ ] **Step 1: Write state-transition tests.**

Assert the exact path PENDING -> EXTRACTING -> EMBEDDING -> CONFLICT_CHECKING -> REVIEW_REQUIRED, failure to FAILED, source mutation to STALE, lease expiry reclaim, duplicate queue delivery no-op, and no target publication from any worker stage.

- [ ] **Step 2: Configure a bounded executor and worker lease.**

Add properties for worker count, queue capacity, lease duration, retry limit, conflict top-K, and min score. Use a named ThreadPoolExecutor with backpressure. Claim using mapper compare-and-set and renew the lease between document batches; log migrationJobId, knowledgeSetKey, source version, target version, and step on every transition.

- [ ] **Step 3: Wire the worker pipeline.**

Create the target snapshot, call StructuredKnowledgeExtractionService.extractChunks(targetDocument, targetChunks, preferredModelId), fail the job if any extraction batch or vector is incomplete, call FactConflictService.check, then set REVIEW_REQUIRED. Persist progress after each chunk/unit batch. Never call a publish method from the worker.

- [ ] **Step 4: Verify retry and crash behavior, then commit.**

~~~powershell
mvn -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin -am -Dtest=KnowledgeMigrationJobServiceTest,KnowledgeMigrationWorkerTest test
git add feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/KnowledgeMigrationStatus.java feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/KnowledgeMigrationJobService.java feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/KnowledgeMigrationWorker.java feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/config/KnowledgeMigrationExecutorConfig.java feisheng-bot-parent/feisheng-bot-admin/src/main/resources/application.yml
git commit -m "feat: run knowledge migrations asynchronously"
~~~

### Task 6: Add conflict resolution and document-level review APIs

**Files:**
- Create: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/KnowledgeMigrationReviewService.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/controller/KnowledgeMigrationController.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/service/KnowledgeMigrationReviewServiceTest.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/controller/KnowledgeMigrationControllerTest.java
- Modify: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/StructuredKnowledgeUnitReviewService.java
- Create: feisheng-bot-parent/sql/42_add_knowledge_migration_permissions.sql

**Interfaces:**
- KnowledgeMigrationReviewService.resolveConflict(Long jobId, Long conflictId, ResolutionRequest request, Long reviewerId) records ADOPT_TARGET, KEEP_SOURCE, MERGE, SPLIT_SCOPE, or NOT_CONFLICT.
- KnowledgeMigrationReviewService.confirmDocument(Long jobId, Long reviewerId) returns GateReport and records mandatory first-version human confirmation only when all checks pass.
- Controller endpoints: POST /api/admin/knowledge/migrations, GET /{id}, POST /{id}/retry, GET /{id}/conflicts, POST /{id}/conflicts/{conflictId}/resolve, POST /{id}/review/confirm, POST /{id}/switch, and POST /api/admin/knowledge/sets/{knowledgeSetKey}/rollback.

- [ ] **Step 1: Write gate and resolution tests.**

Test every blocker: unreviewed unit, missing vector, missing evidence, unresolved blocking/warning conflict, stale source hash, and zero-conflict job without confirmation. Assert valid confirmation records reviewer/time and changes status to READY_TO_SWITCH; idempotent confirmation returns the existing GateReport.

- [ ] **Step 2: Implement resolution validation and audit data.**

Validate conflict ownership, reviewer permission, and allowed resolution for severity. Persist before/after values and reason. Re-running conflict detection preserves prior decisions only when the pair and source hash are unchanged; otherwise create a new pending result.

- [ ] **Step 3: Remove unconditional semantic-unit publication side effects.**

Keep StructuredKnowledgeUnitReviewService's APPROVED transition and evidence checks, but route index synchronization through the published-document filter. Add a test that approving a unit in a target DRAFT document never returns it from structured search.

- [ ] **Step 4: Add admin permissions and verify controllers.**

Add idempotent permissions under knowledge:manage: knowledge:migration:view, knowledge:migration:review, knowledge:migration:switch, and knowledge:migration:rollback; grant them to the existing admin role. Run:

~~~powershell
mvn -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin -am -Dtest=KnowledgeMigrationReviewServiceTest,KnowledgeMigrationControllerTest,StructuredKnowledgeUnitReviewServiceTest test
git add feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/KnowledgeMigrationReviewService.java feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/controller/KnowledgeMigrationController.java feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/StructuredKnowledgeUnitReviewService.java feisheng-bot-parent/sql/42_add_knowledge_migration_permissions.sql
git commit -m "feat: gate knowledge migration on human review"
~~~

### Task 7: Build shadow indexes and atomically switch or roll back versions

**Files:**
- Modify: feisheng-bot-parent/feisheng-bot-knowledge/src/main/java/com/feisheng/bot/knowledge/service/StructuredKnowledgeUnitIndexService.java
- Modify: feisheng-bot-parent/feisheng-bot-knowledge/src/main/java/com/feisheng/bot/knowledge/service/KnowledgeIndexService.java
- Modify: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/KnowledgeDocumentReleaseService.java
- Modify: feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/mapper/BotKnowledgeDocumentMapper.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/service/KnowledgeDocumentReleaseMigrationTest.java
- Modify: feisheng-bot-parent/feisheng-bot-knowledge/src/test/java/com/feisheng/bot/knowledge/service/StructuredKnowledgeUnitIndexServiceTest.java

**Interfaces:**
- StructuredKnowledgeUnitIndexService.buildShadowIndex(Long targetDocumentId) returns ShadowIndexHandle.
- StructuredKnowledgeUnitIndexService.validateShadowIndex(ShadowIndexHandle handle) returns ShadowValidation(success, expectedUnits, indexedUnits, smokeFailures).
- KnowledgeDocumentReleaseService.switchMigration(Long jobId, Long operatorId) returns ReleaseResult only after a successful gate and shadow validation.
- KnowledgeDocumentReleaseService.rollback(String knowledgeSetKey, Long targetDocumentId, Long operatorId) returns the restored ReleaseResult.

- [ ] **Step 1: Add version-filtered snapshot tests.**

Assert structured and regular chunk indexes ignore DRAFT and ARCHIVED document versions, honor effective date windows, and produce one active version per knowledgeSetKey. Assert the target's approved units become visible only after the target document is published.

- [ ] **Step 2: Implement shadow-index build and validation.**

Build a target-only snapshot without replacing the live snapshot. Validate unit count, vector dimension/model/content hash, evidence references, and a configured smoke-query set whose hits all carry the target document ID. Keep the old live snapshot untouched if any check fails.

- [ ] **Step 3: Implement guarded atomic switch.**

Acquire the knowledgeSetKey lock, reload the job and source hash, verify READY_TO_SWITCH, then in one transaction update target to PUBLISHED, source to ARCHIVED with effectiveTo, update supersedesDocumentId/publishedAt, and mark the job COMPLETED. Use conditional SQL with publish status and lock version and reject a second switch with HTTP 409. Trigger live index sync only after commit.

- [ ] **Step 4: Implement rollback and failure preservation.**

Rollback selects a retained archived version, performs the same guarded transaction in reverse, and records an audit event. Index or transaction failure leaves the previous active version serving. Add tests for concurrent switches, source mutation between gate and switch, index failure, and successful rollback.

- [ ] **Step 5: Run backend regression and commit.**

~~~powershell
mvn -f feisheng-bot-parent/pom.xml -pl feisheng-bot-knowledge -am -Dtest=StructuredKnowledgeUnitIndexServiceTest test
mvn -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin -am -Dtest=KnowledgeDocumentReleaseMigrationTest,KnowledgeDocumentReleaseServiceTest test
git add feisheng-bot-parent/feisheng-bot-knowledge/src/main/java/com/feisheng/bot/knowledge/service/StructuredKnowledgeUnitIndexService.java feisheng-bot-parent/feisheng-bot-knowledge/src/main/java/com/feisheng/bot/knowledge/service/KnowledgeIndexService.java feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/KnowledgeDocumentReleaseService.java feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/mapper/BotKnowledgeDocumentMapper.java
git commit -m "feat: atomically switch knowledge document versions"
~~~

### Task 8: Add migration management UI

**Files:**
- Create: feisheng-bot-admin-ui/src/views/knowledge/MigrationJobs.vue
- Create: feisheng-bot-admin-ui/src/views/knowledge/MigrationDetail.vue
- Modify: feisheng-bot-admin-ui/src/views/knowledge/KnowledgeLayout.vue
- Modify: feisheng-bot-admin-ui/src/router/index.js
- Modify: feisheng-bot-admin-ui/src/views/knowledge/SemanticUnits.vue

**Interfaces:**
- Consume the controller endpoints from Task 6 using the existing request client.
- Display MigrationJobView, GateReport, and conflict evidence fields without inventing client-side decisions.

- [ ] **Step 1: Add routes and navigation permission.**

Add /knowledge/migrations and /knowledge/migrations/:id routes, a knowledge:migration:view permission, and a navigation tab in KnowledgeLayout.vue. Keep the existing semantic-unit route unchanged.

- [ ] **Step 2: Build the job list and progress view.**

Show source/target IDs, version, status, progress, blocking counts, last error, retry action, and detail link. Poll only while a job is active and stop on COMPLETED, FAILED, or STALE.

- [ ] **Step 3: Build conflict and document review controls.**

Render source/target evidence, similarity, scope relation, type, severity, rule result, LLM JSON, and resolution controls. Provide a document-level confirmation panel listing every blocker and disable switch until the server returns a passing GateReport. Add switch and rollback confirmations with clear error messages.

- [ ] **Step 4: Verify the production build.**

~~~powershell
npm --prefix feisheng-bot-admin-ui run build
git add feisheng-bot-admin-ui/src/views/knowledge/MigrationJobs.vue feisheng-bot-admin-ui/src/views/knowledge/MigrationDetail.vue feisheng-bot-admin-ui/src/views/knowledge/KnowledgeLayout.vue feisheng-bot-admin-ui/src/router/index.js feisheng-bot-admin-ui/src/views/knowledge/SemanticUnits.vue
git commit -m "feat: add knowledge migration review console"
~~~

### Task 9: Add observability, permissions, and end-to-end acceptance coverage

**Files:**
- Modify: feisheng-bot-parent/feisheng-bot-admin/pom.xml
- Modify: feisheng-bot-parent/feisheng-bot-admin/src/main/resources/application.yml
- Create: feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/KnowledgeMigrationEndToEndTest.java
- Create: feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/KnowledgeMigrationObservabilityTest.java

**Interfaces:**
- Use MeterRegistry counters/timers when Actuator is enabled; log structured fields on every task transition regardless of metrics availability.
- End-to-end fixture API creates source/target documents, chunks, units, conflict rows, and active-version assertions without external models.

- [ ] **Step 1: Add metrics and configuration.**

Add spring-boot-starter-actuator only to the admin module if not already transitively present. Register counters/timers for stage duration, extraction/vector/model failures, candidate/conflict/unknown counts, review time, shadow-index failures, switch success, and rollback count. Configure queue/lease/retry/threshold/retention values with explicit environment-variable defaults in admin application.yml.

- [ ] **Step 2: Add audit events and permission assertions.**

Record state transitions, conflict decisions, human confirmation, switch, and rollback with operator, source/target versions, reason, and job ID. Test that a user lacking review or switch permission receives 403 and cannot invoke those endpoints.

- [ ] **Step 3: Write the end-to-end acceptance test.**

Cover semantic rewrites recalling candidates; different scopes not becoming conflicts; zero-conflict jobs without confirmation failing to switch; unresolved conflicts and unreviewed units blocking; failures leaving old answers online; successful switches serving only target units; rollback restoring the old version; repeated queue delivery and repeated switch being idempotent.

- [ ] **Step 4: Run the complete verification suite.**

~~~powershell
mvn -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin -am test
mvn -f feisheng-bot-parent/pom.xml -am test
npm --prefix feisheng-bot-admin-ui run build
git diff --check
~~~

Expected: focused and regression tests pass, UI build succeeds, and git diff --check reports no whitespace errors. Commit only the implementation files from this task:

~~~powershell
git add feisheng-bot-parent/feisheng-bot-admin/pom.xml feisheng-bot-parent/feisheng-bot-admin/src/main/resources/application.yml feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/KnowledgeMigrationEndToEndTest.java feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/KnowledgeMigrationObservabilityTest.java
git commit -m "test: verify knowledge migration release gates"
~~~

## Execution Notes

Implement tasks in order because each task exposes the interfaces consumed by the next one. Keep the first rollout in shadow mode, then enable review-only mode, then gray-release one low-risk knowledgeSetKey before broadening. Do not enable automatic publication or remove the first-version human confirmation in this implementation.

## Plan Self-Review

- **Spec coverage:** schema, async states, source hash/idempotency, vector recall, field-level comparison, LLM boundary handling, evidence persistence, human review, document-level gate, index filtering, shadow validation, atomic switch, rollback, permissions, observability, tests, and staged rollout are each assigned to a task.
- **Placeholder scan:** no unfinished markers or unspecified handling steps are used; thresholds are configuration-driven and calibrated from fixtures before production rollout.
- **Type consistency:** MigrationJobView, SnapshotResult, ConflictQuery, ConflictCandidate, ConflictReport, GateReport, ShadowIndexHandle, and ShadowValidation are defined at the producing task boundary and consumed by later tasks.
- **Scope check:** all work belongs to the approved migration subsystem; existing extraction, review, release, and index services are extended only where required by the document-level activation guarantee.
