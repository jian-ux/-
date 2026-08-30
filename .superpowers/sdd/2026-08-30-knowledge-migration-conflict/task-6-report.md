# Task 6 Report: Conflict Resolution and Document Review Gate

## Implementation

- Added `KnowledgeMigrationReviewService`.
  - Resolves `ADOPT_TARGET`, `KEEP_SOURCE`, `MERGE`, `SPLIT_SCOPE`, and `NOT_CONFLICT` decisions for conflicts owned by the requested job.
  - Requires an authenticated reviewer id, applies the severity restriction for INFO conflicts, and records a JSON audit snapshot containing before/after state, reviewer, timestamp, and reason.
  - Confirms a document only from `REVIEW_REQUIRED`; it blocks unreviewed units, approved units without vectors or evidence, pending BLOCKING/WARNING conflicts, missing target documents, and stale source hashes.
  - On success it records reviewer/time and advances only to `READY_TO_SWITCH`. A repeated confirmation returns the same successful report without replacing the original reviewer.
- Added migration controller routes for creation, retrieval, retry, conflict listing/resolution, document confirmation, switch, and knowledge-set rollback. The authenticated principal supplies operator/reviewer ids. Domain errors return the existing `R` envelope.
- Switch and rollback deliberately return `R.fail(501, ...)`; neither action publishes, archives, or rolls back a document. Task 7 owns those atomic release operations.
- Added route permissions for view, review, switch, and rollback, plus idempotent permission and admin-role grants in SQL.
- Changed semantic-unit review index synchronization to run only for a published `KNOWLEDGE` document. Approving a migration target DRAFT keeps the unit offline.

## TDD Evidence

RED command (after test compilation correction):

```powershell
mvn -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin -am "-Dtest=KnowledgeMigrationReviewServiceTest,KnowledgeMigrationControllerTest,StructuredKnowledgeUnitReviewServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Key RED output: 8 behavior failures, including `FAILED` confirmation being accepted, no resolution audit, missing reviewer/ownership/severity validation, draft approval invoking `indexService.sync()`, and controller switch/rollback returning 500.

Additional RED command for the lookup envelope:

```powershell
mvn -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin -am "-Dtest=KnowledgeMigrationControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Key RED output: `jobLookupReturnsMigrationErrorsInTheApiEnvelope` failed because `MigrationJobException` escaped as a servlet error.

GREEN verification command:

```powershell
mvn -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin,feisheng-bot-knowledge -am "-Dtest=KnowledgeMigrationReviewServiceTest,KnowledgeMigrationControllerTest,StructuredKnowledgeUnitReviewServiceTest,StructuredKnowledgeUnitIndexServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Key GREEN output: admin tests `25` run, `0` failures/errors; index tests `10` run, `0` failures/errors; reactor `BUILD SUCCESS` in `01:05 min`.

## Changed Files

- `feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/config/SecurityConfig.java`
- `feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/controller/KnowledgeMigrationController.java`
- `feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/KnowledgeMigrationReviewService.java`
- `feisheng-bot-parent/feisheng-bot-admin/src/main/java/com/feisheng/bot/admin/service/StructuredKnowledgeUnitReviewService.java`
- `feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/controller/KnowledgeMigrationControllerTest.java`
- `feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/service/KnowledgeMigrationReviewServiceTest.java`
- `feisheng-bot-parent/feisheng-bot-admin/src/test/java/com/feisheng/bot/admin/service/StructuredKnowledgeUnitReviewServiceTest.java`
- `feisheng-bot-parent/sql/42_add_knowledge_migration_permissions.sql`

## Self-review

- No Task 6 path calls `publishDraftGuarded`, `archivePublishedGuarded`, or a document release method.
- Existing snapshot validation moves a job to `STALE` when its source hash changes. This service only blocks such a stale review; it never retries or overrides the stale result.
- Existing conflict persistence is keyed to the immutable migration snapshot/job. It preserves a reviewed decision only for that snapshot; a changed source hash is rejected before conflict detection can reuse the job.
- `StructuredKnowledgeUnitIndexServiceTest` verifies that DRAFT documents are excluded from the authoritative structured-unit candidate snapshot; Task 6 additionally avoids initiating a sync after DRAFT review transitions.
- `git diff --check` was clean before staging.

## Risks

- This task intentionally leaves switch and rollback unavailable with a `501` envelope. Task 7 must replace these routes with its transactional release implementation and keep their permission boundaries.
- Route-level permission behavior is configured in `SecurityConfig`; controller tests exercise authenticated principal propagation and API error envelopes without loading the full JWT filter chain.

## Fix Round 1

Addressed the independent review findings by adding strict vector/evidence/span/confidence validation, an explicit confidence and non-overridable validation policy, exact completed-DRAFT target binding, durable confirmation reason and before/after audit JSON, CAS confirmation persistence with idempotent reload, snapshot-aware conflict re-detection, and the migration 404 envelope for missing-job conflict listings. Switch and rollback remain Task 7-owned `501` responses.

RED evidence:

```powershell
C:\Users\HQJ\.m2\wrapper\dists\apache-maven-3.9.14-bin\1cb7fhup6b5n3bed6kckbrnspv\apache-maven-3.9.14\bin\mvn.cmd -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin -am "-Dtest=KnowledgeMigrationReviewServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Before the policy fix, the new deterministic-WARNING regression was RED: 18 tests run, 1 failure (`doesNotAllowUnknownDeterministicWarningToBeOverridden`). Before the exact-target version fix, the null `targetVersionId` regression was RED: 18 tests run, 1 failure (`confirmationRequiresExactCompletedDraftTargetBoundToTheJob`).

GREEN evidence:

```powershell
C:\Users\HQJ\.m2\wrapper\dists\apache-maven-3.9.14-bin\1cb7fhup6b5n3bed6kckbrnspv\apache-maven-3.9.14\bin\mvn.cmd -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin -am "-Dtest=KnowledgeMigrationReviewServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result: 18 tests run, 0 failures/errors.

```powershell
C:\Users\HQJ\.m2\wrapper\dists\apache-maven-3.9.14-bin\1cb7fhup6b5n3bed6kckbrnspv\apache-maven-3.9.14\bin\mvn.cmd -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin,feisheng-bot-knowledge -am "-Dtest=KnowledgeMigrationReviewServiceTest,KnowledgeMigrationControllerTest,StructuredKnowledgeUnitReviewServiceTest,FactConflictServiceTest,StructuredKnowledgeUnitIndexServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result: 38 tests run, 0 failures/errors; `BUILD SUCCESS`.

```powershell
C:\Users\HQJ\.m2\wrapper\dists\apache-maven-3.9.14-bin\1cb7fhup6b5n3bed6kckbrnspv\apache-maven-3.9.14\bin\mvn.cmd -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin,feisheng-bot-knowledge -am "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result: 249 tests run, 0 failures/errors across the full selected reactor; `BUILD SUCCESS`.

## Fix Round 2

Addressed the scoped re-review findings: fractional source-span offsets are rejected as non-integral or non-finite before substring validation; conflict re-detection now persists and compares a deterministic SHA-256 fingerprint of normalized facts, scope/conditions/metadata, comparison output, retrieval/rule settings, and extractor/prompt/schema inputs; and the production `KnowledgeMigrationReviewService` constructor is explicitly selected with `@Autowired` while the validation-policy constructor remains available to tests.

RED evidence:

```powershell
C:\Users\HQJ\.m2\wrapper\dists\apache-maven-3.9.14-bin\1cb7fhup6b5n3bed6kckbrnspv\apache-maven-3.9.14\bin\mvn.cmd -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin,feisheng-bot-knowledge -am "-Dtest=StructuredKnowledgeUnitReviewServiceTest,FactConflictServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Before the production fixes, the two new regressions failed as expected: `rejectsApprovalWhenEvidenceSpanOffsetIsFractional` reported that no exception was thrown, and `reDetectionResetsReviewedPairWhenJudgmentInputChanges` expected `PENDING` but observed `RESOLVED` (2 failures, 16 other tests passing).

GREEN evidence:

```powershell
C:\Users\HQJ\.m2\wrapper\dists\apache-maven-3.9.14-bin\1cb7fhup6b5n3bed6kckbrnspv\apache-maven-3.9.14\bin\mvn.cmd -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin,feisheng-bot-knowledge -am "-Dtest=KnowledgeMigrationReviewServiceTest,KnowledgeMigrationControllerTest,StructuredKnowledgeUnitReviewServiceTest,StructuredKnowledgeUnitIndexServiceTest,FactConflictServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result: 40 tests run, 0 failures/errors; reactor `BUILD SUCCESS`.

Self-review: the fingerprint is stored inside the existing conflict evidence JSON, so no schema migration is introduced. Legacy rows without the fingerprint intentionally reset to pending on the next detection. Span validation checks the raw Jackson number node before its conversion to the integer record fields. Constructor selection does not remove the test-configurable policy path.

Risks: changing any normalized judgment input or rule/model metadata deliberately requires a fresh human review; existing reviewed rows generated before Round 2 will also be re-reviewed once. Switch and rollback remain Task 7-owned and unchanged.
