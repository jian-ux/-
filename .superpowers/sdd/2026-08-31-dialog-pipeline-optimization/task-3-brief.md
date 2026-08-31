# Task 3 brief

Implement the Outbox portion of `docs/superpowers/plans/2026-08-31-dialog-pipeline-optimization.md`.

Requirements:
- Keep customer identity scoped to `channelType + channelUserId`; no cross-channel merge.
- Add migration `feisheng-bot-parent/sql/44_add_dialog_memory_outbox.sql` with status, attempts, available_at, locked_until, dedup_key, pending/lease indexes and unique dedup key.
- Put all Outbox Java code and tests under `feisheng-bot-parent/feisheng-bot-core`.
- Provide event constants CONTEXT_SUMMARY, PROFILE_AI_EXTRACTION, CUSTOMER_LONG_TERM_SUMMARY, MEDIA_OCR_MEMORY.
- `enqueue(...)` must reject unknown/blank event keys, deduplicate by stable key, initialize PENDING/attempts/availableAt, and tolerate concurrent duplicate insert.
- `processBatch(int)` must claim with a lease, process idempotently, mark DONE, retry transient RuntimeException with bounded exponential backoff, and mark FAILED at max attempts. Error messages must redact credentials and be length bounded.
- Add a bounded scheduler/executor configuration for Outbox work and observable queue/retry metrics where existing project patterns permit.
- Wire request path so context compression, AI profile extraction, long-term summary and OCR are enqueued after message persistence; first response must not wait for background work. Preserve deterministic explicit profile corrections synchronously.
- Media/OCR remains customer memory and must not enter knowledge-base RAG facts automatically.
- Add focused tests for deduplication, claim/lease behavior, retry/backoff, idempotent processing and migration contract. Run the focused tests and core test subset.

Do not add keyword intent rules or unrelated refactors.
