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
