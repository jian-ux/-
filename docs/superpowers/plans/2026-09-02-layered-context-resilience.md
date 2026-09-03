# Layered Context Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task.

**Goal:** Make the model-backed context-decision path bounded, observable, and resilient, so cloud-model degradation cannot delay customer replies for 60-97 seconds or discard a valid fast-model decision.

**Architecture:** Add a context-only model-call gateway between `IntentUnderstandingService` and exact-model calls. The gateway applies per-tier time budgets, classifies provider failures, permits schema-to-plain-JSON fallback only for unsupported schemas, and uses an independent per-model circuit breaker. `LayeredContextDecisionService` owns the one-turn deadline and model cascade: fast model, primary deep model, optional deep backup, then a valid fast decision or conservative original-query fallback.

**Tech Stack:** Java 17, Spring Boot 3, Resilience4j 2.2, RestTemplate, JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-09-01-layered-context-management-design.md`

## Global Constraints

- Do not infer follow-up context through keywords. This change governs model reliability only.
- Do not alter global answer-generation timeout or retry settings.
- A retry of the same model after a context call is allowed only when the provider explicitly rejects JSON Schema.
- Context calls use no retry by default and must obey the whole-turn deadline.
- A valid, validator-approved fast decision is preferred over conservative fallback after deeper calls fail.
- The backup model is opt-in and must be distinct from fast and primary deep model IDs.
- Circuit breakers are context-only and keyed by configured model ID.
- Diagnostics must not contain original prompts, customer content, credentials, or unredacted model output.

### Task 1: Classify Exact-Model Call Failures and Support Per-Request Policy

**Files:**

- Create: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/dto/LlmFailureType.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/dto/ChatResponse.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/client/LlmHttpClient.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/impl/AiModelServiceImpl.java`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/client/LlmHttpClientTest.java`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/impl/AiModelServiceImplTest.java`

**Interfaces:**

- `LlmFailureType`: `NONE`, `TIMEOUT`, `RATE_LIMIT`, `SERVER_ERROR`, `SCHEMA_UNSUPPORTED`, `INVALID_OUTPUT`, `MODEL_UNAVAILABLE`, `CLIENT_ERROR`.
- `ChatResponse#getFailureType()` identifies failed calls without parsing display text; successful responses are `NONE`.
- `LlmHttpClient#callWithPolicy(...)` and `callJsonSchemaWithPolicy(...)` accept request-local read timeout and max retries.
- `AiModelServiceImpl#chatWithExactModelWithPolicy(...)` and `chatWithExactModelJsonWithPolicy(...)` retain exact-model semantics and never route to another provider.

- [ ] **Step 1: Write failing HTTP-client tests.**

```java
@Test
void classifiesSocketReadTimeoutWithoutRetryingWhenPolicyDisablesRetries() {
    ChatResponse response = client.callWithPolicy(url, key, model, system, prompt,
        provider, 3000, 0);

    assertFalse(response.isSuccess());
    assertEquals(LlmFailureType.TIMEOUT, response.getFailureType());
    assertEquals(1, server.getRequestCount());
}

@Test
void classifiesProviderSchemaRejectionSeparatelyFromServiceFailure() {
    server.enqueue(new MockResponse().setResponseCode(400)
        .setBody("{\\\"error\\\":{\\\"message\\\":\\\"uniqueItems is unsupported\\\"}}"));

    ChatResponse response = client.callJsonSchemaWithPolicy(url, key, model, system,
        prompt, provider, schema, 3000, 0);

    assertEquals(LlmFailureType.SCHEMA_UNSUPPORTED, response.getFailureType());
}
```

- [ ] **Step 2: Run the focused client tests and confirm they fail because request-local classification APIs do not exist.**

Run: `mvn -B -ntp -o -pl feisheng-bot-core -am -Dtest=LlmHttpClientTest test`

- [ ] **Step 3: Add failure type to `ChatResponse`, classify HTTP/transport/empty-output failures, and add request-local normal and JSON-schema call variants.**

Map socket/read/connect timeout to `TIMEOUT`, HTTP 429 to `RATE_LIMIT`, HTTP 5xx to `SERVER_ERROR`, response-format/schema rejection to `SCHEMA_UNSUPPORTED`, malformed successful provider body to `INVALID_OUTPUT`, and unavailable model/configuration to `MODEL_UNAVAILABLE`. Preserve the existing global methods by delegating to the existing default timeout/retry values.

- [ ] **Step 4: Add exact-model policy methods to `AiModelServiceImpl` and test that they call only the requested active model with the provided timeout/retry values.**

```java
verify(llmHttpClient).callJsonSchemaWithPolicy(
    eq("http://model"), eq("key"), eq("model"), anyString(), anyString(),
    eq("dashscope"), anyMap(), eq(8000), eq(0));
```

- [ ] **Step 5: Run the two focused test classes and commit only Task 1 files.**

Run: `mvn -B -ntp -o -pl feisheng-bot-core -am -Dtest=LlmHttpClientTest,AiModelServiceImplTest test`

### Task 2: Add Context-Only Policy, Schema Fallback, and Per-Model Circuit Breakers

**Files:**

- Create: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/ContextModelCallPolicy.java`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/ContextModelCallService.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/IntentUnderstandingService.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/resources/application.yml`
- Create: `feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/ContextModelCallServiceTest.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/IntentUnderstandingServiceTest.java`

**Interfaces:**

- `ContextModelCallPolicy` exposes tiers `FAST`, `DEEP`, `BACKUP`, their bounded timeouts, no-retry policy, and `remainingTimeoutMs(long deadlineNanos)`.
- `ContextModelCallService#callJsonDecision(Long modelId, String prompt, String schemaPrompt, Map<String, Object> schema, ContextModelCallPolicy.Tier tier, long deadlineNanos)` returns `CallResult(ChatResponse response, long latencyMs, boolean schemaFallbackUsed, boolean circuitOpen)`.
- `IntentUnderstandingService#decideContext(TurnContext, Long, ContextModelCallPolicy.Tier, long)` retains parsing behavior and propagates a typed failure reason in `ContextModelResult`.

- [ ] **Step 1: Write failing gateway tests.**

```java
@Test
void retriesSameModelOnlyAfterSchemaUnsupported() {
    when(ai.chatWithExactModelJsonWithPolicy(...)).thenReturn(failed(SCHEMA_UNSUPPORTED));
    when(ai.chatWithExactModelWithPolicy(...)).thenReturn(success(validDecisionJson));

    CallResult result = gateway.callJsonDecision(5L, prompt, schemaPrompt, schema,
        Tier.DEEP, deadlineAfterMillis(8000));

    assertTrue(result.schemaFallbackUsed());
    verify(ai).chatWithExactModelWithPolicy(...);
}

@Test
void doesNotRepeatModelAfterTimeoutAndOpensOnlyItsContextCircuit() {
    when(ai.chatWithExactModelJsonWithPolicy(...)).thenReturn(failed(TIMEOUT));

    gateway.callJsonDecision(5L, prompt, schemaPrompt, schema, Tier.DEEP, deadline);

    verify(ai, never()).chatWithExactModelWithPolicy(...);
    assertEquals(LlmFailureType.TIMEOUT, result.response().getFailureType());
}
```

- [ ] **Step 2: Run focused tests and confirm they fail because the gateway and policy do not exist.**

Run: `mvn -B -ntp -o -pl feisheng-bot-core -am -Dtest=ContextModelCallServiceTest test`

- [ ] **Step 3: Implement the policy and gateway.**

Use default limits fast 3000 ms, deep 8000 ms, backup 4000 ms, total 15000 ms, and 0 retries. Clamp every request timeout to remaining whole-turn budget. Register/fetch a Resilience4j circuit breaker named `context-model-<modelId>` from the independent `contextModel` configuration. On failed typed response call `onError`; on success call `onSuccess`; return `CIRCUIT_OPEN` without a provider call when permission is denied. Do not count `SCHEMA_UNSUPPORTED` as an operational breaker failure when plain JSON fallback succeeds.

- [ ] **Step 4: Route structured context calls in `IntentUnderstandingService` through the gateway and allow plain JSON fallback only for `SCHEMA_UNSUPPORTED`.**

Keep the strict JSON parser and validator contract. Timeout, rate-limit, server error, circuit-open, and model-unavailable responses must return without a second call to the same model.

- [ ] **Step 5: Add configuration, execute focused tests, and commit only Task 2 files.**

```yaml
customer-service:
  layered-context:
    fast-timeout-ms: ${CUSTOMER_SERVICE_LAYERED_CONTEXT_FAST_TIMEOUT_MS:3000}
    deep-timeout-ms: ${CUSTOMER_SERVICE_LAYERED_CONTEXT_DEEP_TIMEOUT_MS:8000}
    backup-timeout-ms: ${CUSTOMER_SERVICE_LAYERED_CONTEXT_BACKUP_TIMEOUT_MS:4000}
    overall-timeout-ms: ${CUSTOMER_SERVICE_LAYERED_CONTEXT_OVERALL_TIMEOUT_MS:15000}
    max-retries: ${CUSTOMER_SERVICE_LAYERED_CONTEXT_MAX_RETRIES:0}
resilience4j:
  circuitbreaker:
    instances:
      contextModel:
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 1
```

Run: `mvn -B -ntp -o -pl feisheng-bot-core -am -Dtest=ContextModelCallServiceTest,IntentUnderstandingServiceTest test`

### Task 3: Enforce the Whole-Turn Model Cascade and Fast-Layer Safety Net

**Files:**

- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/LayeredContextDecisionService.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/java/com/feisheng/bot/core/service/impl/DialogServiceImpl.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/main/resources/application.yml`
- Modify: `.env.example`
- Modify: `docker-compose.yml`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/LayeredContextDecisionServiceTest.java`
- Modify: `feisheng-bot-parent/feisheng-bot-core/src/test/java/com/feisheng/bot/core/service/impl/DialogServiceImplTest.java`

**Interfaces:**

- `LayeredContextDecisionService#decide(TurnContext, Long fastModelId, Long deepModelId, Long backupModelId)` applies one deadline and records each tier outcome.
- `DecisionResult` adds `backupModelId`, per-tier latency and failure type, `overallLatencyMs`, `usedFastFallback`, `circuitState`, and final context source while retaining existing route/decision fields for callers.
- Existing three-argument `decide` delegates to the new overload with no backup, maintaining source compatibility.

- [ ] **Step 1: Write failing cascade tests.**

```java
@Test
void usesValidatedFastDecisionWhenDeepModelTimesOut() {
    when(intent.decideContext(context, 11L, Tier.FAST, anyLong())).thenReturn(success(lowConfidenceFast));
    when(intent.decideContext(context, 22L, Tier.DEEP, anyLong())).thenReturn(failed(TIMEOUT));

    DecisionResult result = service.decide(context, 11L, 22L, null);

    assertEquals(Route.FAST_FALLBACK, result.route());
    assertEquals(lowConfidenceFast.resolvedQuery(), result.decision().resolvedQuery());
    assertTrue(result.usedFastFallback());
}

@Test
void triesDistinctBackupAfterPrimaryDeepRateLimit() {
    when(intent.decideContext(context, 22L, Tier.DEEP, anyLong())).thenReturn(failed(RATE_LIMIT));
    when(intent.decideContext(context, 33L, Tier.BACKUP, anyLong())).thenReturn(success(deepDecision));

    assertEquals(Route.BACKUP_MODEL, service.decide(context, 11L, 22L, 33L).route());
}
```

- [ ] **Step 2: Run focused cascade tests and confirm they fail because the current implementation returns generic fallback and has no backup model route.**

Run: `mvn -B -ntp -o -pl feisheng-bot-core -am -Dtest=LayeredContextDecisionServiceTest test`

- [ ] **Step 3: Implement deadline-aware cascade.**

Call fast once. Escalate only when the validated fast decision requires it. Call primary deep model once, then call a configured backup only for terminal deep failure (`TIMEOUT`, `RATE_LIMIT`, `SERVER_ERROR`, `INVALID_OUTPUT`, `MODEL_UNAVAILABLE`, `CIRCUIT_OPEN`) and only when it is distinct and time remains. Return `FAST_FALLBACK` with the valid fast decision when deeper calls cannot produce a valid decision. If no valid decision exists, use conservative original-query fallback. Record each outcome without customer text.

- [ ] **Step 4: Wire the optional backup model ID into dialog orchestration and expose bounded diagnostics in existing decision telemetry.**

Add `CUSTOMER_SERVICE_LAYERED_CONTEXT_DEEP_BACKUP_MODEL_ID` with blank/zero default to `.env.example` and Docker pass-through. Preserve existing result consumers and do not put raw prompt, response, or memory text into telemetry.

- [ ] **Step 5: Run focused service and dialog tests, then commit only Task 3 files.**

Run: `mvn -B -ntp -o -pl feisheng-bot-core -am -Dtest=LayeredContextDecisionServiceTest,DialogServiceImplTest test`

### Task 4: Build, Deploy Locally, and Perform Customer-Path Acceptance Tests

**Files:**

- Modify: `docs/evaluation-results/layered-context-customer-acceptance-2026-09-01.md`

- [ ] **Step 1: Add acceptance cases before running them.**

Document expected route and reply behavior for: tutorial follow-up, video-format follow-up, image/text correction, explicit new topic, task resume, cross-session recall, deep timeout with fast safety net, deep rate-limit with backup, circuit-open fast degradation, and unavailable all-model conservative fallback.

- [ ] **Step 2: Run the full core test suite and capture exact Maven summary.**

Run:

```powershell
docker run --rm `
  -v "${PWD}/feisheng-bot-parent:/app" `
  -v "${PWD}/.runtime/m2:/root/.m2/repository" `
  -w /app maven:3.9-eclipse-temurin-17 `
  mvn -B -ntp -o -pl feisheng-bot-core -am test
```

- [ ] **Step 3: Rebuild the customer-service container without resetting databases or volumes.**

Run: `docker compose up -d --build feisheng-bot`

- [ ] **Step 4: Run the documented customer-path requests through the actual service and record observations.**

Use a disposable customer identity only. Record observed response, route/diagnostic fields, request duration, and pass/fail. Treat unavailable upstream cloud model as an infrastructure limitation, not a passed model-quality assertion.

- [ ] **Step 5: Update the acceptance report with only observed evidence and list remaining environment/model risks.**

## Self-Review Checklist

- [ ] Context-model calls no longer inherit 60-second global read timeout or default retries.
- [ ] Schema fallback occurs only for classified schema incompatibility.
- [ ] Timeouts, 429, 5xx, invalid output, unavailable model, and circuit open have distinguishable diagnostics.
- [ ] A valid fast result survives failed deep and backup attempts.
- [ ] Backup models are opt-in, exact, distinct, and timeout-bounded.
- [ ] Circuit failure metrics reflect failed `ChatResponse` values rather than only thrown exceptions.
- [ ] No global answer path behavior or timeout is changed.
- [ ] Full tests and customer-path observations are captured after deployment.
