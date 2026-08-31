# Task 7 implementation report

Implemented and committed as `2388b9b`.

- Added structured and regular shadow-index build/validation APIs.
- Added guarded migration switch with READY_TO_SWITCH, source hash, knowledge-set lock, conditional publish/archive, transaction and post-commit index synchronization.
- Added archived-version rollback and guarded restore mapper SQL.
- Wired switch and rollback controller endpoints.
- Verification reported: admin/knowledge compile, 11 StructuredKnowledgeUnitIndexServiceTest tests, and git diff --check.

## Fix round 1 requested by scoped review

Address only load-bearing release defects: persist supersedes linkage, validate
knowledge-set ownership during switch/rollback, refresh structured index after
commit, strengthen shadow validation against authoritative rows, and add focused
release regression coverage for duplicate switch/source mutation/rollback.

## Fix round 1 completed

- Replaced JVM-only knowledge-set mutex with transactional `SELECT ... FOR UPDATE` locking.
- Switch now requires source, target, and migration job to share the same knowledgeSetKey, publishes with guarded supersedes linkage, and archives every currently published version in the set.
- Rollback validates archived ownership, restores supersedes metadata, and records operator/reason in migration audit fields.
- Shadow validation rejects empty snapshots, invalid dimensions/models/hashes, unresolved target evidence, and count mismatches; regular index uses the same empty/dimension gate.
- Both regular and structured indexes synchronize only after transaction commit.
- Added `KnowledgeDocumentReleaseMigrationTest` for source mutation, duplicate switch, rollback ownership, and post-commit index refresh.

Verification: `KnowledgeDocumentReleaseMigrationTest`, `KnowledgeDocumentReleaseServiceTest`, and `StructuredKnowledgeUnitIndexServiceTest` passed (4 + 2 + 11 tests); reactor compile passed; `git diff --check` passed.
