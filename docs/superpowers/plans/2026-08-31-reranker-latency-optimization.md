# Reranker Latency Optimization Implementation Plan

> **For agentic workers:** Execute the tasks inline in this session using the approved design and keep the test-first checkpoints visible.

**Goal:** Reduce Reranker response latency and tail latency without changing the customer-service fallback contract.

**Architecture:** A single CUDA model process remains resident. Requests first use a normalized bounded cache, then enter a short bounded micro-batch queue handled by one inference worker. The Java retrieval layer sends fewer, shorter candidates and skips Reranker when deterministic evidence already makes it unnecessary.

**Tech Stack:** FastAPI, asyncio, SentenceTransformers CrossEncoder, PyTorch CUDA, Spring Boot, JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-08-31-reranker-latency-design.md`

## Global Constraints

- Keep the model contract as `query + ordered documents -> indexed relevance scores`.
- Keep one GPU model worker; do not add Uvicorn workers that duplicate model memory.
- Preserve fused retrieval fallback on Reranker errors, queue saturation, timeout, or partial results.
- Preserve environment overrides for candidate count, document length, batch size, and timing limits.
- Do not expose or change secret values in `.env`.

---

### Task 1: Reranker unit behavior

**Files:**
- Modify: `services/qwen3-vl-reranker/test_app.py`
- Modify: `services/qwen3-vl-reranker/app.py`

- [ ] Write failing tests for text normalization, multi-request batching with preserved indexes, real warmup invocation, and latency fields.
- [ ] Run `python -m unittest services/qwen3-vl-reranker/test_app.py -v` and confirm the new tests fail for the missing behavior.
- [ ] Implement the bounded cache, micro-batch worker, startup warmup, and response diagnostics.
- [ ] Run the same Python test command and confirm all tests pass.

### Task 2: Retrieval work reduction and safe skips

**Files:**
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/impl/RagRetrievalService.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/impl/RagRetrievalServiceTest.java`

- [ ] Write failing tests for one-candidate skip and confident exact structured candidate skip.
- [ ] Run the focused Maven tests and confirm they fail for the missing skip behavior.
- [ ] Implement the smallest guarded skip in `applyReranking` and preserve diagnostics explaining the skip.
- [ ] Run the focused Maven tests and confirm they pass.

### Task 3: Defaults and operational verification

**Files:**
- Modify: `docker-compose.yml`
- Modify: `.env.example`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/resources/application.yml`
- Modify: `feisheng-bot-parent/feisheng-bot-admin/src/main/resources/application.yml`

- [ ] Change non-secret defaults to six candidates, 1200 document characters, model max length 768, and micro-batch settings with environment overrides.
- [ ] Rebuild and restart only the affected Reranker and bot services.
- [ ] Run Python tests, affected Maven tests, health checks, and sequential/concurrent latency probes.
- [ ] Report actual timings and any residual risk.
