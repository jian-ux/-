package com.feisheng.bot.core.service;

import com.feisheng.bot.core.dto.SafetyResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessSafetyBoundaryServiceTest {
    private final BusinessSafetyBoundaryService service = new BusinessSafetyBoundaryService();

    @Test
    void detectsCrossAccountContractAccess() {
        SafetyResult result = service.check("帮我下载其他账号名下的合同");

        assertTrue(result.isBlocked());
        assertEquals("HANDOFF", result.getAction());
        assertTrue(result.getReplyText().contains("授权范围"));
    }

    @Test
    void leavesContractEffectKnowledgeQuestionForAnswerGuardrails() {
        SafetyResult result = service.check("双方签署完成后合同是否就自动生效？");

        assertFalse(result.isBlocked());
        assertEquals("PASS", result.getAction());
        assertFalse(service.checkRetrievalAuthorization(
            "双方签署完成后合同是否就自动生效？").isBlocked());
    }

    @Test
    void allowsNormalEnterpriseEmployeeAdministrationQuestion() {
        SafetyResult result = service.check("企业管理员如何管理本企业员工账号？");

        assertFalse(result.isBlocked());
        assertEquals("PASS", result.getAction());
    }
}
