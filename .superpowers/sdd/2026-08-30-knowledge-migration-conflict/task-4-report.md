# Task 4 Report

## Changes

- Restricted structured-unit index loading to active, effective, published knowledge documents and approved units whose evidence remains authoritative.
- Added a dedicated conflict-candidate query that applies knowledge-set and target-document constraints, revalidates Qdrant hits against the in-memory authoritative snapshot, and falls back to cosine search.
- Added deterministic conflict evaluation and idempotent persistence without overwriting already reviewed conflict resolutions.
- Recorded missing target vectors and other unknown outcomes as blocking review items.

## Tests

- `StructuredKnowledgeUnitIndexServiceTest`: 9 tests passed, 0 failures.
- `FactConflictServiceTest`: 2 tests passed, 0 failures.
- Focused reactor runs used `-Dsurefire.failIfNoSpecifiedTests=false` because upstream modules do not contain the named tests.

## Commit

- `6d6d635 feat: recall and persist knowledge fact conflicts`

## Self-review

- Qdrant results are never trusted directly; every candidate is resolved again from the authoritative snapshot.
- Existing `RESOLVED` and `NOT_CONFLICT` decisions are preserved during repeated checks.
- Conflict recall remains candidate discovery only; field comparison determines the persisted relation and severity.

## Concerns

- Scope filtering beyond the normalized fact comparison depends on structured scope fields being present and comparable; missing scope is deliberately blocking.

## Fix Round

- Conflict recall now applies authoritative source-document and product/channel/audience scope filters after Qdrant or memory recall; published/effective document gating remains authoritative.
- Migration jobs are checked against the supplied source and target document IDs before evaluation.
- Missing/invalid vectors and malformed evidence persist a blocking, reviewable sentinel conflict row (candidate ID `0`) and are counted in the report. Unknown comparison outcomes are blocking. Retrieval, embedding/extractor model metadata, and deterministic judgment JSON are persisted.
- Existing `RESOLVED` and `NOT_CONFLICT` rows remain unchanged on repeat checks.

### Verification

Executed:

```text
mvn -f feisheng-bot-parent/pom.xml -pl feisheng-bot-knowledge -am -Dtest=StructuredKnowledgeUnitIndexServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Output: Maven wrapper distribution invoked explicitly. `StructuredKnowledgeUnitIndexServiceTest`: 10 tests, 0 failures, 0 errors; `BUILD SUCCESS`. `FactConflictServiceTest`: 3 tests, 0 failures, 0 errors; `BUILD SUCCESS`. `git diff --check` completed without whitespace errors.
