package com.feisheng.bot.knowledge.controller;

import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.knowledge.service.StructuredKnowledgeUnitIndexService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticUnitControllerTest {
    @Test
    void forwardsEmbeddingLimitsAndTrustedFilters() {
        StructuredKnowledgeUnitIndexService service = mock(
            StructuredKnowledgeUnitIndexService.class);
        Map<String, Object> filters = Map.of("categoryId", 42);
        when(service.search(List.of(1.0, 0.0), 50, 0.4, filters))
            .thenReturn(List.of(Map.of("semanticUnitId", 1L)));
        SemanticUnitController controller = new SemanticUnitController(service);

        R<List<Map<String, Object>>> response = controller.semanticUnitSearch(Map.of(
            "embedding", List.of(1, 0), "topK", 100, "minScore", 0.4,
            "filters", filters));

        assertEquals(1L, response.getData().get(0).get("semanticUnitId"));
        verify(service).search(List.of(1.0, 0.0), 50, 0.4, filters);
    }
}
