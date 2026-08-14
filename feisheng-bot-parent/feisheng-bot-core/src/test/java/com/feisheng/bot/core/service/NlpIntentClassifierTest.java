package com.feisheng.bot.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NlpIntentClassifierTest {
    private final NlpIntentClassifier classifier = new NlpIntentClassifier();

    @Test
    void recognizesLoanContractDraftingFromCombinedSignals() {
        NlpIntentClassifier.IntentAnalysis result =
            classifier.classify("我要签借款合同，这个怎么写？");

        assertEquals(NlpIntentClassifier.IntentCode.CONTRACT_DRAFTING, result.intentCode());
        assertEquals("借款合同", result.subject());
        assertEquals("借款合同 内容怎么写 起草模板", result.retrievalQuery());
        assertTrue(result.needsClarification());
        assertFalse(result.requiresSpecificEvidence());
    }

    @Test
    void distinguishesExistingContractSigningOperationFromDrafting() {
        NlpIntentClassifier.IntentAnalysis result =
            classifier.classify("我已经有合同了，怎么上传发起签署？");

        assertEquals(NlpIntentClassifier.IntentCode.CONTRACT_SIGNING_OPERATION,
            result.intentCode());
        assertTrue(result.needsClarification());
        assertEquals("发起合同有几种方式？ 已有合同文件怎么上传发起签署，已签纸质合同怎么上传归档？",
            result.retrievalQuery());
    }

    @Test
    void recognizesSpecificContractCapabilityAndRequiresMatchingEvidence() {
        NlpIntentClassifier.IntentAnalysis result =
            classifier.classify("你们平台可以签房屋买卖合同吗？");

        assertEquals(NlpIntentClassifier.IntentCode.CONTRACT_TYPE_CAPABILITY,
            result.intentCode());
        assertEquals("房屋买卖合同", result.subject());
        assertEquals("点签 是否支持签署 房屋买卖合同", result.retrievalQuery());
        assertFalse(result.requiresSpecificEvidence());
        assertTrue(result.generallySupportedContractType());
    }

    @Test
    void quantityQuestionDemonstratesWhyTheRawQueryMustBeRetained() {
        NlpIntentClassifier.IntentAnalysis result =
            classifier.classify("批量发起合同支持多少份同时操作？");

        assertEquals(NlpIntentClassifier.IntentCode.CONTRACT_TYPE_CAPABILITY,
            result.intentCode());
        assertEquals("点签 是否支持签署 合同", result.retrievalQuery());
        assertFalse(result.retrievalQuery().contains("多少份"));
    }

    @Test
    void keepsUnknownContractTypesBehindSpecificEvidenceGuard() {
        NlpIntentClassifier.IntentAnalysis result =
            classifier.classify("你们平台支持签量子合同吗？");

        assertEquals(NlpIntentClassifier.IntentCode.CONTRACT_TYPE_CAPABILITY,
            result.intentCode());
        assertEquals("量子合同", result.subject());
        assertTrue(result.requiresSpecificEvidence());
        assertFalse(result.generallySupportedContractType());
    }

    @Test
    void recognizesContractLegalRisk() {
        NlpIntentClassifier.IntentAnalysis result =
            classifier.classify("电子合同有法律效力吗？");

        assertEquals(NlpIntentClassifier.IntentCode.CONTRACT_LEGAL_RISK,
            result.intentCode());
        assertEquals(NlpIntentClassifier.RiskLevel.HIGH, result.riskLevel());
    }

    @Test
    void recognizesProductFeaturesAndUsageOnlyFromCombinedSignals() {
        assertEquals(NlpIntentClassifier.IntentCode.PRODUCT_FEATURES,
            classifier.classify("你们平台有哪些功能？").intentCode());
        assertEquals("点签电子合同主要包含的7大功能",
            classifier.classify("你们平台有哪些功能？").retrievalQuery());
        assertEquals(NlpIntentClassifier.IntentCode.PRODUCT_USAGE,
            classifier.classify("点签怎么使用？").intentCode());
    }

    @Test
    void broadEntityWordsAloneDoNotBecomeHighRiskIntents() {
        for (String question : new String[] {"平台", "产品", "功能", "支持", "你们的"}) {
            NlpIntentClassifier.IntentAnalysis result = classifier.classify(question);
            assertEquals(NlpIntentClassifier.IntentCode.UNKNOWN, result.intentCode());
            assertEquals(NlpIntentClassifier.RiskLevel.LOW, result.riskLevel());
        }
    }
}
