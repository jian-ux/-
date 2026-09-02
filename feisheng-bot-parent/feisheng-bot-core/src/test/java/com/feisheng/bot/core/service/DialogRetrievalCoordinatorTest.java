package com.feisheng.bot.core.service;

import com.feisheng.bot.core.dto.QueryVariant;
import com.feisheng.bot.core.service.impl.RagRetrievalService;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DialogRetrievalCoordinatorTest {

    private static final Map<String, Object> KNOWLEDGE_SCOPE = Map.of("sourceScope", "KNOWLEDGE");

    @Test
    void retrievesEveryExplicitSubQuestionAndKeepsTheirEvidenceSeparated() {
        RagRetrievalService retrievalService = mock(RagRetrievalService.class);
        DialogRetrievalCoordinator coordinator = new DialogRetrievalCoordinator(retrievalService);
        String first = "点签企业认证需要哪些材料";
        String second = "点签支持批量发起合同吗";
        when(retrievalService.retrieve(first, KNOWLEDGE_SCOPE, true))
                .thenReturn(retrieval(first, "营业执照和法定代表人信息"));
        when(retrievalService.retrieve(second, KNOWLEDGE_SCOPE, true))
                .thenReturn(retrieval(second, "支持批量发起合同"));

        RagRetrievalService.RetrievalResult result = coordinator.retrieve(
                "点签企业认证需要哪些材料，支持批量发起合同吗？",
                List.of(), null, null, KNOWLEDGE_SCOPE, true);

        assertTrue(result.answerable());
        assertFalse(result.directAnswer());
        assertEquals("compound_rag", result.decision());
        assertTrue(result.context().contains("【子问题 1】" + first));
        assertTrue(result.context().contains("【子问题 2】" + second));
        assertEquals(2, result.decisionDiagnostics().get("subQuestionCount"));
        assertEquals(2, result.decisionDiagnostics().get("answeredSubQuestionCount"));
        verify(retrievalService).retrieve(first, KNOWLEDGE_SCOPE, true);
        verify(retrievalService).retrieve(second, KNOWLEDGE_SCOPE, true);
        verify(retrievalService, never()).retrieve(
                eq("点签企业认证需要哪些材料，支持批量发起合同吗？"), eq(KNOWLEDGE_SCOPE), eq(true));
    }

    @Test
    void marksTheEvidencePartialWhenOneSubQuestionCannotBeGrounded() {
        RagRetrievalService retrievalService = mock(RagRetrievalService.class);
        DialogRetrievalCoordinator coordinator = new DialogRetrievalCoordinator(retrievalService);
        String first = "点签企业认证需要哪些材料";
        String second = "点签支持批量发起合同吗";
        when(retrievalService.retrieve(first, KNOWLEDGE_SCOPE, true))
                .thenReturn(retrieval(first, "营业执照和法定代表人信息"));
        when(retrievalService.retrieve(second, KNOWLEDGE_SCOPE, true))
                .thenReturn(new RagRetrievalService.RetrievalResult(
                        false, false, null, null, 0.0, "not_found", false,
                        Collections.emptyList(), Collections.emptyList()));

        RagRetrievalService.RetrievalResult result = coordinator.retrieve(
                "点签企业认证需要哪些材料，支持批量发起合同吗？",
                List.of(), null, null, KNOWLEDGE_SCOPE, true);

        assertTrue(result.answerable());
        assertEquals("partial_rag", result.decision());
        assertEquals(1, result.decisionDiagnostics().get("answeredSubQuestionCount"));
        assertTrue(result.context().contains("【子问题 2】" + second));
        assertTrue(result.context().contains("暂无可核实知识"));
    }

    private RagRetrievalService.RetrievalResult retrieval(String query, String answer) {
        return new RagRetrievalService.RetrievalResult(
                true, true, answer, "问题：" + query + "\n答案：" + answer,
                0.91, "structured_qa", true,
                List.of(Map.of("ref", query, "snippet", answer)), Collections.emptyList());
    }
}
