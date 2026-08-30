package com.feisheng.bot.admin.controller;

import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.admin.service.*;
import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.knowledge.service.KnowledgeIndexService;
import com.feisheng.bot.knowledge.service.MinioStorageService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AdminDocControllerTest {
    @Test
    void compatibilityConstructorReturnsUnavailableForMigrate() {
        AdminDocController controller = new AdminDocController(mock(BotKnowledgeDocumentMapper.class), mock(BotKnowledgeChunkMapper.class),
            mock(DocumentParseService.class), mock(ChunkingService.class), mock(EmbeddingService.class), mock(VectorSearchService.class),
            mock(MinioStorageService.class), mock(KnowledgeIndexService.class), mock(ImageOcrService.class), mock(ImportQualityService.class),
            mock(KnowledgeChunkPersistenceService.class), mock(StructuredQaReviewService.class), mock(KnowledgeDocumentReleaseService.class));
        assertEquals(503, controller.migrate(1L, null).getCode());
    }

    @Test
    void deletingSharedDraftKeepsSourceObject() throws Exception {
        BotKnowledgeDocument document = new BotKnowledgeDocument();
        document.setId(2L); document.setBucketName(null); document.setObjectKey("shared/source.pdf");
        BotKnowledgeDocumentMapper documents = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeChunkMapper chunks = mock(BotKnowledgeChunkMapper.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        when(documents.selectById(2L)).thenReturn(document);
        when(documents.countActiveObjectReferences(null, "shared/source.pdf")).thenReturn(2);
        when(chunks.selectList(any())).thenReturn(java.util.List.of());
        AdminDocController controller = new AdminDocController(documents, chunks,
            mock(DocumentParseService.class), mock(ChunkingService.class), mock(EmbeddingService.class),
            mock(VectorSearchService.class), storage, mock(KnowledgeIndexService.class),
            mock(ImageOcrService.class), mock(ImportQualityService.class),
            mock(KnowledgeChunkPersistenceService.class), mock(StructuredQaReviewService.class),
            mock(KnowledgeDocumentReleaseService.class));
        R<Void> response = controller.delete(2L);
        assertEquals(200, response.getCode());
        verify(storage, never()).delete(anyString());
        verify(chunks).delete(any());
        verify(documents).deleteById(2L);
    }
}
