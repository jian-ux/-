package com.feisheng.bot.core.service;

import com.feisheng.bot.core.entity.BotMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerServiceDecisionEngineTest {
    private CustomerServiceDecisionEngine engine;
    private NlpIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        engine = new CustomerServiceDecisionEngine(new ObjectMapper());
        classifier = new NlpIntentClassifier();
    }

    @Test
    void asksForTheMissingContractType() {
        CustomerServiceDecisionEngine.ClarificationPlan result =
            engine.initialClarification(
                "点签支持签合同吗？", classifier.classify("点签支持签合同吗？"));
        CustomerServiceDecisionEngine.ClarificationPlan contextual =
            engine.initialClarification(
                "这个合同能签吗？", classifier.classify("这个合同能签吗？"));

        assertEquals("CONTRACT_TYPE_CAPABILITY", result.intentCode());
        assertEquals("contractType", result.missingSlot());
        assertEquals(1, result.attempt());
        assertEquals(2, result.maxAttempts());
        assertNull(contextual);
    }

    @Test
    void asksWhichAccountOperationInsteadOfGuessing() {
        CustomerServiceDecisionEngine.ClarificationPlan generic =
            engine.initialClarification("账号怎么弄？", classifier.classify("账号怎么弄？"));
        CustomerServiceDecisionEngine.ClarificationPlan specific =
            engine.initialClarification(
                "账号怎么登录？", classifier.classify("账号怎么登录？"));

        assertEquals("accountAction", generic.missingSlot());
        assertNull(specific);
    }

    @Test
    void resolvesAProductOperationFromPendingMetadata() throws Exception {
        CustomerServiceDecisionEngine.ClarificationPlan clarification =
            engine.pendingClarification(
                classifier.classify("点签怎么使用？"),
                "点签可以通过微信小程序使用。请问您具体想进行发起合同、签署合同，还是企业认证？");
        BotMessage ai = message("ai", clarification.question());
        ai.setMetadata(new ObjectMapper().writeValueAsString(Map.of(
            "pendingClarification", clarification.toState())));

        CustomerServiceDecisionEngine.PendingResult result = engine.resolvePending(List.of(
            message("user", "点签怎么使用？"), ai,
            message("user", "我想发起合同")), "我想发起合同");

        assertEquals(CustomerServiceDecisionEngine.PendingStatus.RESOLVED, result.status());
        assertEquals("点签 发起合同 怎么操作", result.resolvedQuery());
    }

    @Test
    void extractsTheContractTypeFromANaturalReply() throws Exception {
        CustomerServiceDecisionEngine.ClarificationPlan clarification =
            engine.initialClarification(
                "点签支持签合同吗？", classifier.classify("点签支持签合同吗？"));
        BotMessage ai = message("ai", clarification.question());
        ai.setMetadata(new ObjectMapper().writeValueAsString(Map.of(
            "pendingClarification", clarification.toState())));

        CustomerServiceDecisionEngine.PendingResult result = engine.resolvePending(List.of(
            message("user", "点签支持签合同吗？"), ai,
            message("user", "我想签劳动合同")), "我想签劳动合同");

        assertEquals(CustomerServiceDecisionEngine.PendingStatus.RESOLVED, result.status());
        assertEquals("点签 是否支持签署 劳动合同", result.resolvedQuery());
    }

    @Test
    void retriesOnceThenHandsOffWhenTheSlotIsStillMissing() throws Exception {
        CustomerServiceDecisionEngine.ClarificationPlan first =
            engine.initialClarification(
                "点签支持签合同吗？", classifier.classify("点签支持签合同吗？"));
        CustomerServiceDecisionEngine.PendingResult retry = engine.resolvePending(
            conversationWithPending(first, "不知道"), "不知道");

        assertEquals(CustomerServiceDecisionEngine.PendingStatus.RETRY, retry.status());
        assertEquals(2, retry.clarification().attempt());

        CustomerServiceDecisionEngine.PendingResult handoff = engine.resolvePending(
            conversationWithPending(retry.clarification(), "还是不知道"), "还是不知道");

        assertEquals(CustomerServiceDecisionEngine.PendingStatus.HANDOFF, handoff.status());
        assertEquals("clarification_exhausted", handoff.reasonCode());
    }

    @Test
    void boundsGenericLowConfidenceClarification() throws Exception {
        CustomerServiceDecisionEngine.ClarificationPlan first =
            engine.genericClarification("请补充具体场景");

        CustomerServiceDecisionEngine.PendingResult retry = engine.resolvePending(
            conversationWithPending(first, "还是不清楚"), "还是不清楚");
        assertEquals(CustomerServiceDecisionEngine.PendingStatus.RETRY, retry.status());
        assertEquals(2, retry.clarification().attempt());

        CustomerServiceDecisionEngine.PendingResult handoff = engine.resolvePending(
            conversationWithPending(retry.clarification(), "我不知道"), "我不知道");
        assertEquals(CustomerServiceDecisionEngine.PendingStatus.HANDOFF, handoff.status());
        assertEquals("clarification_exhausted", handoff.reasonCode());
    }

    @Test
    void handsContractContentDraftingToAQualifiedAgent() throws Exception {
        CustomerServiceDecisionEngine.ClarificationPlan clarification =
            engine.pendingClarification(
                classifier.classify("我要签借款合同，这个怎么写？"),
                "请回复“合同内容”或“发起签署”，我会继续为您处理。");
        BotMessage ai = message("ai", clarification.question());
        ai.setMetadata(new ObjectMapper().writeValueAsString(Map.of(
            "pendingClarification", clarification.toState())));

        CustomerServiceDecisionEngine.PendingResult result = engine.resolvePending(List.of(
            message("user", "我要签借款合同，这个怎么写？"), ai,
            message("user", "合同内容")), "合同内容");

        assertEquals(CustomerServiceDecisionEngine.PendingStatus.HANDOFF, result.status());
        assertEquals("contract_drafting_requires_handoff", result.reasonCode());
    }

    @Test
    void normalizesLegacyAnswerStatesIntoFourDecisions() {
        Map<String, Object> noAnswer = new LinkedHashMap<>();
        noAnswer.put("answerDecision", "NO_KNOWLEDGE");
        noAnswer.put("answerStatus", "no_answer");
        noAnswer.put("source", "no_answer");
        noAnswer.put("fallbackDecision", "rag_abstained");

        engine.enrich(noAnswer);

        assertEquals("NO_ANSWER",
            ((Map<?, ?>) noAnswer.get("serviceDecision")).get("decision"));
        assertEquals("rag_abstained",
            ((Map<?, ?>) noAnswer.get("serviceDecision")).get("reasonCode"));
    }

    @Test
    void blocksHighRiskModelCallWhenEvidenceIsMissing() {
        CustomerServiceDecisionEngine.GateResult result = engine.beforeModelCall(
            classifier.classify("电子合同有法律效力吗？"),
            false, false, 0.0, 0.55, false, false);

        assertFalse(result.modelAllowed());
        assertEquals(CustomerServiceDecisionEngine.Decision.HANDOFF, result.decision());
        assertEquals("high_risk_no_evidence", result.reasonCode());
    }

    @Test
    void blocksLowConfidenceGenerativeAnswerBeforeCallingTheModel() {
        CustomerServiceDecisionEngine.GateResult result = engine.beforeModelCall(
            classifier.classify("企业怎么登录？"),
            true, false, 0.35, 0.40, false, false);

        assertFalse(result.modelAllowed());
        assertEquals(CustomerServiceDecisionEngine.Decision.HANDOFF, result.decision());
        assertEquals("low_confidence", result.reasonCode());
    }

    @Test
    void onlyAllowsNoEvidenceNativeFallbackWhenExplicitlyEnabled() {
        CustomerServiceDecisionEngine.GateResult allowed = engine.beforeModelCall(
            classifier.classify("什么是电子签名"),
            false, false, 0.0, 0.40, false, true);
        CustomerServiceDecisionEngine.GateResult denied = engine.beforeModelCall(
            classifier.classify("什么是电子签名"),
            false, false, 0.0, 0.40, false, false);

        assertTrue(allowed.modelAllowed());
        assertEquals("restricted_fallback", allowed.reasonCode());
        assertFalse(denied.modelAllowed());
        assertEquals(CustomerServiceDecisionEngine.Decision.NO_ANSWER, denied.decision());
        assertEquals("no_reliable_evidence", denied.reasonCode());
    }

    private List<BotMessage> conversationWithPending(
            CustomerServiceDecisionEngine.ClarificationPlan clarification,
            String currentQuestion) throws Exception {
        BotMessage ai = message("ai", clarification.question());
        ai.setMetadata(new ObjectMapper().writeValueAsString(Map.of(
            "pendingClarification", clarification.toState())));
        return List.of(
            message("user", "点签支持签合同吗？"), ai,
            message("user", currentQuestion));
    }

    private BotMessage message(String role, String content) {
        BotMessage message = new BotMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
