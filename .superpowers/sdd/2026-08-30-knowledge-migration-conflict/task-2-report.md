# Task 2 Report

## Changes

- Reworked snapshot idempotency to reuse an existing active job by source document and source content hash before allocating a target version.
- Aligned source hashing with `StructuredKnowledgeExtractionService`, including null content handling and ordered chunk data.
- Made target cloning reconcile partial targets without duplicate chunks, and copied complete document metadata including OCR error and expiry fields.
- Protected shared MinIO objects in knowledge and admin delete paths with null-safe reference counting; download URLs remain available for shared objects.
- Added constructor compatibility overload and focused service/controller regression tests.

## Tests

Command:

`mvn -f feisheng-bot-parent/pom.xml -pl feisheng-bot-admin -am -Dtest=KnowledgeMigrationSnapshotServiceTest,AdminDocControllerTest test`

Initial result: default `mvn` command was unavailable in PowerShell. Using the absolute IntelliJ Maven path, the focused command initially failed in the reactor because upstream modules had no matching tests. Re-run with `-Dsurefire.failIfNoSpecifiedTests=false` completed successfully:

`Tests run: 3, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS (38.759 s)`

## Remaining Concerns

- The focused test set is intentionally compact; broader integration coverage remains outside this task.

## Fix Round 2

- Reconcile now matches target chunks by stable chunk position/index, replaces stale content, and removes unmatched extras.
- Source snapshots reject non-published documents.
- Focused rerun (absolute IntelliJ Maven path): `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS (41.204 s)`.

## Fix Round 3

- Reconcile matching remains deterministic by source chunk index and replaces stale same-index content while deleting extras.
- Added regression coverage for stale same-index plus extra chunks and retained published-source validation.
- Focused Maven rerun reached admin compilation but failed on pre-existing missing `com.feisheng.bot.core` symbols across unrelated admin classes; no focused tests executed in that run.

## Fix Round 4

- `cloneTarget` no longer runs inside the outer rollback transaction, so source-hash invalidation updates persist as `STALE` before returning the conflict response.
- Compatibility constructor migration endpoint now returns 503 instead of throwing NPE when no migration service is supplied.
- Source `PUBLISHED` guard and exact index-based stale/extra chunk reconciliation retained.
- Focused command used absolute IntelliJ Maven path with `-Dsurefire.failIfNoSpecifiedTests=false`; admin compilation remains blocked by unrelated missing `com.feisheng.bot.core` symbols, so no tests executed in this round.

## Fix Round 5

- Existing target documents are now validated as completed `DRAFT` snapshots before reconciliation; active/published targets are rejected without mutation.
- Added compatibility `/migrate` regression coverage for the legacy constructor returning 503 when migration service is absent.
- Focused Maven: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS (34.783 s)`.
