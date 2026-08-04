package com.feisheng.bot.core.service.impl;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagEvaluationServiceTest {
    @Test
    void reportsDecisionAndCitationMetrics() {
        RagRetrievalService retrieval = mock(RagRetrievalService.class);
        Map<String, Object> citation = Map.of(
            "sourceType", "faq", "sourceId", 7L, "title", "密码重置");
        when(retrieval.retrieve("怎么重置密码", false)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, true, "点击忘记密码", "ctx", 0.93, "direct", true,
                List.of(citation), Collections.emptyList()));
        when(retrieval.retrieve("火星办公室在哪", false)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.2, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));
        RagEvaluationService service = new RagEvaluationService(retrieval);

        RagEvaluationService.EvaluationReport report = service.evaluate(
            new RagEvaluationService.EvaluationRequest("smoke", List.of(
                new RagEvaluationService.EvaluationCase(
                    "known", "怎么重置密码", true, "faq", 7L),
                new RagEvaluationService.EvaluationCase(
                    "unknown", "火星办公室在哪", false, null, null))));

        assertEquals(1.0, report.decisionAccuracy());
        assertEquals(1.0, report.answerRecall());
        assertEquals(1.0, report.noAnswerRecall());
        assertEquals(1.0, report.citationHitRate());
        assertEquals(1.0, report.answerPrecision());
        assertEquals(1.0, report.noAnswerPrecision());
        assertEquals(1.0, report.sourceHitAtOneRate());
        assertEquals(1.0, report.meanReciprocalRank());
        assertEquals(2, report.cases().size());
    }

    @Test
    void reportsExpectedSourceRankFromCandidates() {
        RagRetrievalService retrieval = mock(RagRetrievalService.class);
        when(retrieval.retrieve("企业认证", false)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, "ctx", 0.8, "rag", true,
                List.of(Map.of("sourceType", "document", "sourceId", 8L)),
                List.of(
                    Map.of("sourceType", "document", "sourceId", 7L),
                    Map.of("sourceType", "document", "sourceId", 8L))));
        RagEvaluationService service = new RagEvaluationService(retrieval);

        RagEvaluationService.EvaluationReport report = service.evaluate(
            new RagEvaluationService.EvaluationRequest("rank", List.of(
                new RagEvaluationService.EvaluationCase(
                    "source-rank", "企业认证", true, "document", 8L))));

        assertEquals(2, report.cases().get(0).sourceRank());
        assertEquals(0.0, report.sourceHitAtOneRate());
        assertEquals(0.5, report.meanReciprocalRank());
    }

    @Test
    void evaluatesMultiTurnCaseWithConversationContext() {
        RagRetrievalService retrieval = mock(RagRetrievalService.class);
        when(retrieval.retrieve("它到期后还能用吗？",
                "用户: 我想了解专业版电子合同套餐", null, false))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                true, false, null, "ctx", 0.81, "rag", true,
                List.of(Map.of("sourceType", "document", "sourceId", 641L)),
                Collections.emptyList()));
        RagEvaluationService service = new RagEvaluationService(retrieval);

        RagEvaluationService.EvaluationReport report = service.evaluate(
            new RagEvaluationService.EvaluationRequest("multi-turn", List.of(
                new RagEvaluationService.EvaluationCase(
                    "expiry-follow-up", "它到期后还能用吗？", true,
                    "document", 641L,
                    List.of(new RagEvaluationService.EvaluationTurn(
                        "user", "我想了解专业版电子合同套餐"))))));

        assertEquals(1.0, report.decisionAccuracy());
        verify(retrieval).retrieve("它到期后还能用吗？",
            "用户: 我想了解专业版电子合同套餐", null, false);
    }

    @Test
    void reportsShadowDiagnosticsWithoutCountingThemInSourceRank() {
        RagRetrievalService retrieval = mock(RagRetrievalService.class);
        Map<String, Object> diagnostic = Map.of(
            "type", "structured_unit_diagnostic",
            "sourceType", "structured_unit_shadow",
            "sourceId", "unit-7",
            "diagnosticOnly", true,
            "evidenceChunkIds", List.of(8L));
        Map<String, Object> evidence = Map.of(
            "sourceType", "document", "sourceId", 8L);
        when(retrieval.retrieve("企业认证", false)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, "ctx", 0.8, "rag", true,
                List.of(evidence), List.of(diagnostic, evidence)));
        RagEvaluationService service = new RagEvaluationService(retrieval);

        RagEvaluationService.EvaluationReport report = service.evaluate(
            new RagEvaluationService.EvaluationRequest("shadow", List.of(
                new RagEvaluationService.EvaluationCase(
                    "shadow-rank", "企业认证", true, "document", 8L))));

        assertEquals(1, report.cases().get(0).sourceRank());
        assertEquals(List.of(diagnostic),
            report.cases().get(0).structuredUnitDiagnostics());
        assertEquals(1.0, report.sourceHitAtOneRate());
    }
}
