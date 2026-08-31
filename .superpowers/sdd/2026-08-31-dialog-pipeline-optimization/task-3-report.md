# Task 3 Report

## Status

Implemented and committed as `eeaa15d` (`perf: move customer memory updates to outbox`).

## Changes

- Added `bot_memory_outbox_event` migration with status/attempt/lease fields, pending and lease indexes, and unique deduplication key.
- Moved Outbox worker and tests into `feisheng-bot-parent/feisheng-bot-core`; removed the erroneous top-level duplicate files.
- Added event constants, stable-key deduplication, bounded retry/backoff, lease claiming, idempotent completion, bounded redacted errors, counters, and bounded scheduled executor configuration.
- Dialog persistence now enqueues context summary, AI profile extraction, long-term memory, and media/OCR events. Deterministic profile corrections remain synchronous; long-term memory updates are no longer on the request path.
- Added focused service/worker and migration contract tests.

## Verification

`git diff --check` passed. Maven was not available in the execution environment (`mvn` command not found), so the focused Maven test commands could not be run.

## Risks

- Event handlers currently use the worker's idempotent processing hook; wiring provider-specific background handlers should be validated in deployment.
- Scheduler defaults are conservative (2 core / 4 max threads, queue 64) and should be tuned against production throughput.
