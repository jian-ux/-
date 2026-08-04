package com.feisheng.bot.knowledge.controller;

import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.knowledge.service.KnowledgeEvidenceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeChunkControllerTest {
    @Test
    void parsesIdsAndForwardsTrustedFilters() {
        KnowledgeEvidenceService service = mock(KnowledgeEvidenceService.class);
        Map<String, Object> filters = Map.of("sourceScope", "PUBLIC");
        when(service.findApprovedChunks(List.of(3L, 1L), filters))
            .thenReturn(List.of(Map.of("chunkId", 3L)));
        KnowledgeChunkController controller = new KnowledgeChunkController(service);

        R<List<Map<String, Object>>> response = controller.evidenceChunks(Map.of(
            "chunkIds", List.of("3", 1), "filters", filters));

        assertEquals(3L, response.getData().get(0).get("chunkId"));
        verify(service).findApprovedChunks(List.of(3L, 1L), filters);
    }
}
