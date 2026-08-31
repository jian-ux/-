# Reranker Latency Optimization Design

**Goal:** Reduce Reranker latency and tail latency while preserving the existing scoring contract and retrieval fallback behavior.

**Current evidence:** `Qwen/Qwen3-Reranker-0.6B` runs through SentenceTransformers CrossEncoder on one CUDA Uvicorn worker. A global asyncio lock serializes requests; warm single-request inference is sub-second, while concurrent requests queue behind the lock.

## Design

1. Keep one GPU inference worker and replace request-wide mutual exclusion with a bounded micro-batch queue. Cache hits return before queue submission. Cache misses wait up to 8ms or until 32 query-document pairs are collected, then one model call serves multiple requests.
2. Normalize query and document whitespace before cache lookup. Preserve document order so response indexes remain stable. Add cache hit, queue wait, inference, and total latency fields to the response and health counters for observability.
3. Run a real small prediction during startup. The service reports `ok` only after model loading and warmup finish.
4. Reduce default retrieval work to six candidates, 1200 document characters, and model max length 768. Keep environment overrides available for quality rollback.
5. Skip Reranker calls when the candidate set is empty, has one candidate, or contains a confident exact structured/FAQ result that is already eligible for direct handling. Existing direct-answer paths remain unchanged.
6. Preserve failure behavior: queue saturation, timeout, model errors, or incomplete results return the existing fused retrieval order rather than failing the customer request.

## Verification

- Python unit tests cover normalization, cache reuse, startup warmup, multi-request batching, index preservation, and latency fields.
- Java unit tests cover candidate compression and the safe skip conditions.
- Run the Reranker test suite, the affected Maven core tests, and live health plus latency probes after rebuilding the service.
