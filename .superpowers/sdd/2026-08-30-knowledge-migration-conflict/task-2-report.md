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

Result: not runnable in this environment. PowerShell reports `mvn` is not recognized; no Maven test execution occurred.

## Remaining Concerns

- Maven/JDK build should be run in an environment with Maven available to verify compilation and execute the focused tests.
