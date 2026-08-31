package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.knowledge.service.KnowledgeIndexService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeDocumentReleaseServiceTest {
    @Test
    void publishesReviewedVectorizedVersionAndRefreshesIndex() {
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        BotKnowledgeDocument draft = document(2L, "product-manual", 2, "DRAFT");
        BotKnowledgeDocument old = document(1L, "product-manual", 1, "PUBLISHED");
        BotKnowledgeChunk chunk = new BotKnowledgeChunk();
        chunk.setDocumentId(2L);
        chunk.setStatus("APPROVED");
        chunk.setEmbedding("[1,0]");
        when(documentMapper.selectById(2L)).thenReturn(draft);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
        when(documentMapper.selectForUpdateByKnowledgeSetKey("product-manual")).thenReturn(List.of(old, draft));

        KnowledgeDocumentReleaseService service = new KnowledgeDocumentReleaseService(
            documentMapper, chunkMapper, indexService);
        KnowledgeDocumentReleaseService.ReleaseResult result = service.publish(2L);

        assertEquals("PUBLISHED", result.publishStatus());
        assertEquals(1L, result.supersededDocumentId());
        assertEquals("PUBLISHED", draft.getPublishStatus());
        assertNotNull(draft.getPublishedAt());
        assertEquals(1L, draft.getSupersedesDocumentId());
        verify(documentMapper).updateById(old);
        verify(documentMapper).updateById(draft);
        verify(indexService).sync();
    }

    @Test
    void rejectsPublicationWhenAnyChunkIsNotApproved() {
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        BotKnowledgeDocument draft = document(2L, "product-manual", 2, "DRAFT");
        BotKnowledgeChunk chunk = new BotKnowledgeChunk();
        chunk.setDocumentId(2L);
        chunk.setStatus("PENDING");
        chunk.setEmbedding("[1,0]");
        when(documentMapper.selectById(2L)).thenReturn(draft);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
        KnowledgeDocumentReleaseService service = new KnowledgeDocumentReleaseService(
            documentMapper, chunkMapper, indexService);

        KnowledgeDocumentReleaseService.ReleaseException error = assertThrows(
            KnowledgeDocumentReleaseService.ReleaseException.class, () -> service.publish(2L));

        assertEquals(409, error.status());
        verify(documentMapper, never()).updateById(any(BotKnowledgeDocument.class));
        verify(indexService, never()).sync();
    }

    private BotKnowledgeDocument document(Long id, String key, int version, String status) {
        BotKnowledgeDocument document = new BotKnowledgeDocument();
        document.setId(id);
        document.setStatus(2);
        document.setKnowledgeSetKey(key);
        document.setDocumentVersion(version);
        document.setPublishStatus(status);
        document.setPriority(0);
        return document;
    }
}
