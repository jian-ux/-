package com.feisheng.bot.knowledge.service;

import com.feisheng.bot.knowledge.entity.BotKnowledgeChunk;
import com.feisheng.bot.knowledge.entity.BotKnowledgeDocument;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeDocumentMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeEvidenceServiceTest {
    @Test
    void returnsOnlyApprovedUndeletedEvidenceInRequestOrder() {
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        when(chunkMapper.selectList(any())).thenReturn(List.of(
            chunk(1L, "APPROVED", 0),
            chunk(2L, "PENDING", 0),
            chunk(3L, "APPROVED", 0),
            chunk(4L, "APPROVED", 1)));
        when(documentMapper.selectList(any())).thenReturn(List.of(document()));
        KnowledgeEvidenceService service = new KnowledgeEvidenceService(chunkMapper, documentMapper);

        List<Map<String, Object>> result = service.findApprovedChunks(
            List.of(3L, 2L, 4L, 1L), Map.of("categoryId", 42));

        assertEquals(List.of(3L, 1L), result.stream()
            .map(value -> ((Number) value.get("chunkId")).longValue()).toList());
        assertEquals("original chunk 3", result.get(0).get("content"));
        assertEquals("PUBLIC", result.get(0).get("sourceScope"));
        assertFalse(result.get(0).containsKey("statement"));
        assertFalse(result.get(0).containsKey("answer"));
    }

    @Test
    void rejectsEvidenceOutsideTrustedFilters() {
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(1L, "APPROVED", 0)));
        when(documentMapper.selectList(any())).thenReturn(List.of(document()));
        KnowledgeEvidenceService service = new KnowledgeEvidenceService(chunkMapper, documentMapper);

        assertEquals(List.of(), service.findApprovedChunks(
            List.of(1L), Map.of("categoryId", 99)));
    }

    @Test
    void rejectsEvidenceWhileDocumentIsStillProcessing() {
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(1L, "APPROVED", 0)));
        BotKnowledgeDocument processing = document();
        processing.setStatus(1);
        when(documentMapper.selectList(any())).thenReturn(List.of(processing));
        KnowledgeEvidenceService service = new KnowledgeEvidenceService(chunkMapper, documentMapper);

        assertEquals(List.of(), service.findApprovedChunks(List.of(1L), Map.of()));
    }

    private BotKnowledgeChunk chunk(Long id, String status, int deleted) {
        BotKnowledgeChunk chunk = new BotKnowledgeChunk();
        chunk.setId(id);
        chunk.setDocumentId(7L);
        chunk.setChunkIndex(id.intValue());
        chunk.setContent("original chunk " + id);
        chunk.setSectionPath("Account > Signing");
        chunk.setStatus(status);
        chunk.setDeleted(deleted);
        return chunk;
    }

    private BotKnowledgeDocument document() {
        BotKnowledgeDocument document = new BotKnowledgeDocument();
        document.setId(7L);
        document.setTitle("Signing manual");
        document.setMediaType("DOCUMENT");
        document.setCategoryId(42L);
        document.setSourceScope("PUBLIC");
        document.setStatus(2);
        document.setDeleted(0);
        return document;
    }
}
