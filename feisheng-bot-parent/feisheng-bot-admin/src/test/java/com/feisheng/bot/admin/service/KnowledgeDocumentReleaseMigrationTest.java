package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.entity.BotKnowledgeMigrationJob;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeMigrationJobMapper;
import com.feisheng.bot.knowledge.service.KnowledgeIndexService;
import com.feisheng.bot.knowledge.service.StructuredKnowledgeUnitIndexService;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeDocumentReleaseMigrationTest {
    @Test
    void sourceMutationBlocksSwitchBeforeAnyPublishUpdate() {
        Fixture f = fixture();
        when(f.chunks.selectList(any())).thenReturn(List.of(chunk(10L, "changed")));
        KnowledgeDocumentReleaseService.ReleaseException error = assertThrows(
            KnowledgeDocumentReleaseService.ReleaseException.class,
            () -> f.service.switchMigration(7L, 9L));
        assertEquals(409, error.status());
        verify(f.documents, never()).publishDraftWithSupersedesGuarded(any(), any(), any(), any());
    }

    @Test
    void duplicateSwitchIsRejectedByDraftGuard() {
        Fixture f = fixture();
        when(f.documents.publishDraftWithSupersedesGuarded(any(), any(), any(), any())).thenReturn(0);
        KnowledgeDocumentReleaseService.ReleaseException error = assertThrows(
            KnowledgeDocumentReleaseService.ReleaseException.class,
            () -> f.service.switchMigration(7L, 9L));
        assertEquals(409, error.status());
        verify(f.documents, never()).archivePublishedGuarded(any(), any());
    }

    @Test
    void switchRefreshesRegularAndStructuredIndexesAfterCommit() {
        Fixture f = fixture();
        when(f.documents.publishDraftWithSupersedesGuarded(any(), any(), any(), any())).thenReturn(1);
        when(f.documents.archivePublishedGuarded(any(), any())).thenReturn(1);
        KnowledgeDocumentReleaseService.ReleaseResult result = f.service.switchMigration(7L, 9L);
        assertEquals("PUBLISHED", result.publishStatus());
        verify(f.regular).sync();
        verify(f.structured).sync();
    }

    @Test
    void rollbackRejectsArchivedVersionFromAnotherKnowledgeSet() {
        Fixture f = fixture();
        BotKnowledgeDocument foreign = document(3L, "other", "ARCHIVED");
        when(f.documents.selectById(3L)).thenReturn(foreign);
        KnowledgeDocumentReleaseService.ReleaseException error = assertThrows(
            KnowledgeDocumentReleaseService.ReleaseException.class,
            () -> f.service.rollback("set-a", 3L, 9L));
        assertEquals(409, error.status());
        verify(f.documents, never()).restoreArchivedGuarded(any(), any(), any(), any());
    }

    private Fixture fixture() {
        BotKnowledgeDocumentMapper documents = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeChunkMapper chunks = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeMigrationJobMapper jobs = mock(BotKnowledgeMigrationJobMapper.class);
        KnowledgeIndexService regular = mock(KnowledgeIndexService.class);
        StructuredKnowledgeUnitIndexService structured = mock(StructuredKnowledgeUnitIndexService.class);
        BotKnowledgeDocument source = document(1L, "set-a", "PUBLISHED");
        BotKnowledgeDocument target = document(2L, "set-a", "DRAFT");
        target.setDocumentVersion(2);
        BotKnowledgeMigrationJob job = new BotKnowledgeMigrationJob();
        job.setId(7L); job.setSourceDocumentId(1L); job.setTargetDocumentId(2L);
        job.setKnowledgeSetKey("set-a"); job.setStatus("READY_TO_SWITCH");
        job.setSourceContentHash(hash("source"));
        when(jobs.findByIdForUpdate(7L)).thenReturn(job);
        when(documents.selectById(1L)).thenReturn(source);
        when(documents.selectById(2L)).thenReturn(target);
        when(documents.selectPublishedForUpdateByKnowledgeSetKey("set-a")).thenReturn(List.of(source));
        when(chunks.selectList(any())).thenReturn(List.of(chunk(10L, "source")));
        when(structured.buildShadowIndex(2L)).thenReturn(new StructuredKnowledgeUnitIndexService.ShadowIndexHandle(
            2L, List.of(new StructuredKnowledgeUnitIndexService.ShadowUnit(20L, List.of(1D, 0D), "m", "hash-valid", List.of(10L))), true, null));
        when(structured.validateShadowIndex(any())).thenReturn(new StructuredKnowledgeUnitIndexService.ShadowValidation(true, 1, 1, List.of()));
        when(regular.buildShadowIndex(2L)).thenReturn(new KnowledgeIndexService.ShadowIndexHandle(2L, List.of(new KnowledgeIndexService.ShadowPoint(10L, List.of(1D, 0D), "m", "hash-valid")), true, null));
        when(regular.validateShadowIndex(any())).thenReturn(new KnowledgeIndexService.ShadowValidation(true, 1, 1, List.of()));
        KnowledgeDocumentReleaseService service = new KnowledgeDocumentReleaseService(documents, chunks, regular, structured, jobs);
        return new Fixture(service, documents, chunks, regular, structured, jobs);
    }

    private static BotKnowledgeDocument document(Long id, String key, String status) {
        BotKnowledgeDocument d = new BotKnowledgeDocument(); d.setId(id); d.setStatus(2);
        d.setKnowledgeSetKey(key); d.setPublishStatus(status); d.setSourceScope("KNOWLEDGE"); d.setDeleted(0); return d;
    }
    private static BotKnowledgeChunk chunk(Long id, String content) {
        BotKnowledgeChunk c = new BotKnowledgeChunk(); c.setId(id); c.setDocumentId(1L); c.setChunkIndex(0); c.setContent(content); return c;
    }
    private static String hash(String content) {
        return com.feisheng.bot.common.util.EmbeddingMetadataUtil.contentHash("10\0" + "0\0" + content + "\1");
    }
    private record Fixture(KnowledgeDocumentReleaseService service, BotKnowledgeDocumentMapper documents,
                           BotKnowledgeChunkMapper chunks, KnowledgeIndexService regular,
                           StructuredKnowledgeUnitIndexService structured, BotKnowledgeMigrationJobMapper jobs) {}
}
