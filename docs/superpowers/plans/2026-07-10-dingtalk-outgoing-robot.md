# DingTalk Outgoing Robot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the DingTalk outgoing robot callback path so text messages are processed by the existing dialog engine and returned as DingTalk text replies.

**Architecture:** Keep DingTalk protocol adaptation inside the gateway controller. The controller verifies signatures, normalizes DingTalk payloads into `ChannelMessageDTO`, delegates to `ChannelServiceImpl`, and maps the dialog result back into DingTalk's text response shape.

**Tech Stack:** Java 17, Spring Boot 3, JUnit 5, Mockito, Maven.

---

### Task 1: Add Gateway Test Coverage

**Files:**
- Modify: `feisheng-bot-parent/feisheng-bot-gateway/pom.xml`
- Create: `feisheng-bot-parent/feisheng-bot-gateway/src/test/java/com/feisheng/bot/gateway/util/DingTalkCryptoUtilTest.java`
- Create: `feisheng-bot-parent/feisheng-bot-gateway/src/test/java/com/feisheng/bot/gateway/controller/DingTalkControllerTest.java`

- [ ] **Step 1: Add test dependency**

Add `spring-boot-starter-test` with test scope to the gateway module.

- [ ] **Step 2: Write failing signature tests**

Create tests proving a computed DingTalk signature verifies and an altered signature fails.

- [ ] **Step 3: Write failing controller tests**

Create tests for:

- valid `text.content` payload returns `{"msgtype":"text","text":{"content":"..."}}`
- empty text returns a DingTalk text fallback
- invalid signature returns HTTP 401 when a secret is configured

- [ ] **Step 4: Run red tests**

Run:

```powershell
mvn -pl feisheng-bot-gateway -Dtest=DingTalkCryptoUtilTest,DingTalkControllerTest test
```

Expected: controller tests fail because the current controller returns the generic project wrapper and does not accept injected DingTalk secret configuration.

### Task 2: Implement DingTalk Response Adapter

**Files:**
- Modify: `feisheng-bot-parent/feisheng-bot-gateway/src/main/java/com/feisheng/bot/gateway/controller/DingTalkController.java`

- [ ] **Step 1: Inject DingTalk secret**

Replace direct `System.getenv("DINGTALK_APP_SECRET")` access with constructor-injected configuration using `${dingtalk.app-secret:${DINGTALK_APP_SECRET:}}`.

- [ ] **Step 2: Return protocol response**

Change the callback method to return `ResponseEntity<Object>`. Keep invalid signature as `401`, and return DingTalk text maps for successful processing and recoverable errors.

- [ ] **Step 3: Normalize payload**

Support `text.content`, current `content.content`, `senderStaffId`, `senderId`, `conversationId`, `msgId`, and `msgtype`.

- [ ] **Step 4: Extract reply**

Use `reply` from `ChannelServiceImpl.processMessage`. If absent, use a short fallback message.

- [ ] **Step 5: Run green tests**

Run:

```powershell
mvn -pl feisheng-bot-gateway -Dtest=DingTalkCryptoUtilTest,DingTalkControllerTest test
```

Expected: all tests pass.

### Task 3: Verify Module Compatibility

**Files:**
- No source changes unless verification reveals compile issues.

- [ ] **Step 1: Run gateway tests through Maven reactor**

Run:

```powershell
mvn -pl feisheng-bot-gateway -am test
```

Expected: Maven compiles dependencies and gateway tests pass.

- [ ] **Step 2: Review changed files**

Run:

```powershell
git diff -- feisheng-bot-parent/feisheng-bot-gateway docs/superpowers
```

Expected: diff is limited to gateway tests, gateway controller, gateway pom, and docs.
