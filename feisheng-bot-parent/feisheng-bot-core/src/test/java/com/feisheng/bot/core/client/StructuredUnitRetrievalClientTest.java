package com.feisheng.bot.core.client;

import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.knowledge.controller.KnowledgeChunkController;
import com.feisheng.bot.knowledge.controller.SemanticUnitController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StructuredUnitRetrievalClientTest {
    @Mock private SemanticUnitController semanticUnitController;
    @Mock private KnowledgeChunkController knowledgeChunkController;

    @Test
    void searchesCandidateOnlyUnitsUsingEmbeddingAndTrustedFilters() {
        StructuredUnitRetrievalClient client = new StructuredUnitRetrievalClient(
            semanticUnitController, knowledgeChunkController);
        Map<String, Object> filters = Map.of("categoryId", 9L);
        when(semanticUnitController.semanticUnitSearch(anyMap())).thenReturn(R.ok(List.of(
            Map.of(
                "semanticUnitId", "unit-1",
                "similarity", 0.91,
                "evidenceChunkIds", List.of(11L, 12L),
                "statement", "该字段只能用于候选诊断",
                "candidateOnly", true),
            Map.of(
                "semanticUnitId", "unit-unsafe",
                "similarity", 0.99,
                "evidenceChunkIds", List.of(99L),
                "candidateOnly", false))));

        List<StructuredUnitRetrievalClient.StructuredUnitHit> hits = client.search(
            List.of(1.0, 0.0), 5, filters);

        assertEquals(1, hits.size());
        assertEquals("unit-1", hits.get(0).semanticUnitId());
        assertEquals(0.91, hits.get(0).score());
        assertEquals(List.of(11L, 12L), hits.get(0).evidenceChunkIds());
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(semanticUnitController).semanticUnitSearch(request.capture());
        assertEquals(List.of(1.0, 0.0), request.getValue().get("embedding"));
        assertEquals(5, request.getValue().get("topK"));
        assertEquals(filters, request.getValue().get("filters"));
    }

    @Test
    void resolvesEvidenceInRequestedOrderAndRemovesDirectAnswerFields() {
        StructuredUnitRetrievalClient client = new StructuredUnitRetrievalClient(
            semanticUnitController, knowledgeChunkController);
        Map<String, Object> second = new HashMap<>();
        second.put("chunkId", 12L);
        second.put("content", "第二段原文");
        second.put("sectionPath", null);
        second.put("answer", "不得使用的抽取答案");
        second.put("fullAnswer", "不得使用的完整抽取答案");
        second.put("directAnswerEligible", true);
        Map<String, Object> first = new HashMap<>();
        first.put("chunkId", 11L);
        first.put("content", "第一段原文");
        when(knowledgeChunkController.evidenceChunks(anyMap())).thenReturn(R.ok(List.of(
            second, Map.of("chunkId", 99L, "content", "未请求内容"), first)));

        List<Map<String, Object>> evidence = client.evidenceChunks(
            List.of(11L, 12L), Map.of("sourceScope", "customer"));

        assertEquals(List.of(11L, 12L), evidence.stream()
            .map(chunk -> chunk.get("chunkId")).toList());
        assertEquals(null, evidence.get(1).get("sectionPath"));
        assertFalse(evidence.get(1).containsKey("answer"));
        assertFalse(evidence.get(1).containsKey("fullAnswer"));
        assertFalse(evidence.get(1).containsKey("directAnswerEligible"));
        assertEquals(false, evidence.get(1).get("structuredQa"));
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(knowledgeChunkController).evidenceChunks(request.capture());
        assertEquals(List.of(11L, 12L), request.getValue().get("chunkIds"));
        assertEquals(Map.of("sourceScope", "customer"), request.getValue().get("filters"));
    }

    @Test
    void rejectsUnitWhoseDeclaredEvidenceExceedsSafetyLimit() {
        StructuredUnitRetrievalClient client = new StructuredUnitRetrievalClient(
            semanticUnitController, knowledgeChunkController);
        List<Long> tooManyEvidenceIds = LongStream.rangeClosed(1, 51).boxed().toList();
        when(semanticUnitController.semanticUnitSearch(anyMap())).thenReturn(R.ok(List.of(
            Map.of(
                "semanticUnitId", "unit-oversized",
                "similarity", 0.99,
                "evidenceChunkIds", tooManyEvidenceIds,
                "candidateOnly", true))));

        List<StructuredUnitRetrievalClient.StructuredUnitHit> hits = client.search(
            List.of(1.0, 0.0), 5, Map.of("sourceScope", "KNOWLEDGE"));

        assertEquals(List.of(), hits);
    }
}
