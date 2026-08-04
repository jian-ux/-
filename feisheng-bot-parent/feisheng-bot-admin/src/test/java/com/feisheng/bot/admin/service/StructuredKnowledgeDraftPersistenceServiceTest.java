package com.feisheng.bot.admin.service;

import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeSemanticUnitMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredKnowledgeDraftPersistenceServiceTest {
    @Test
    void updatesDraftPreservesReviewedAndRetiresMissingDraft() {
        BotKnowledgeSemanticUnitMapper mapper = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeSemanticUnit currentDraft = unit(1L, "a", "DRAFT");
        BotKnowledgeSemanticUnit approved = unit(2L, "b", "APPROVED");
        BotKnowledgeSemanticUnit staleDraft = unit(3L, "c", "DRAFT");
        when(mapper.selectList(any())).thenReturn(
            List.of(currentDraft, approved, staleDraft));
        StructuredKnowledgeDraftPersistenceService service =
            new StructuredKnowledgeDraftPersistenceService(mapper);

        StructuredKnowledgeDraftPersistenceService.PersistResult result =
            service.replaceDrafts(5L, "source", List.of(
                unit(null, "a", "DRAFT"), unit(null, "b", "DRAFT")), true);

        assertEquals(0, result.inserted());
        assertEquals(1, result.updated());
        assertEquals(1, result.preservedReviewed());
        assertEquals(1, result.retired());
        assertEquals("REJECTED", staleDraft.getStatus());
        verify(mapper, times(2)).updateById(any(BotKnowledgeSemanticUnit.class));
        verify(mapper, never()).insert(any(BotKnowledgeSemanticUnit.class));
    }

    @Test
    void partialExtractionDoesNotRetireUnseenDrafts() {
        BotKnowledgeSemanticUnitMapper mapper = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeSemanticUnit staleDraft = unit(3L, "c", "DRAFT");
        when(mapper.selectList(any())).thenReturn(List.of(staleDraft));
        StructuredKnowledgeDraftPersistenceService service =
            new StructuredKnowledgeDraftPersistenceService(mapper);

        StructuredKnowledgeDraftPersistenceService.PersistResult result =
            service.replaceDrafts(5L, "source", List.of(), false);

        assertEquals(0, result.retired());
        assertEquals("DRAFT", staleDraft.getStatus());
        verify(mapper, never()).updateById(any(BotKnowledgeSemanticUnit.class));
    }

    @Test
    void refusesToPersistNonDraftCandidate() {
        BotKnowledgeSemanticUnitMapper mapper = mock(BotKnowledgeSemanticUnitMapper.class);
        StructuredKnowledgeDraftPersistenceService service =
            new StructuredKnowledgeDraftPersistenceService(mapper);

        assertThrows(IllegalArgumentException.class,
            () -> service.replaceDrafts(5L, "source",
                List.of(unit(null, "a", "APPROVED"))));
        verify(mapper, never()).insert(any(BotKnowledgeSemanticUnit.class));
    }

    private BotKnowledgeSemanticUnit unit(Long id, String key, String status) {
        BotKnowledgeSemanticUnit unit = new BotKnowledgeSemanticUnit();
        unit.setId(id);
        unit.setDocumentId(5L);
        unit.setSourceHash("source");
        unit.setUnitKey(key);
        unit.setStatus(status);
        return unit;
    }
}
