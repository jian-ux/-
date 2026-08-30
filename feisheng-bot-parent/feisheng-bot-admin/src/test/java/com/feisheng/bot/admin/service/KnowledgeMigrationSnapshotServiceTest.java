package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.entity.BotKnowledgeMigrationJob;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeMigrationJobMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeMigrationSnapshotServiceTest {
    @Test
    void rejectsMissingSource() {
        BotKnowledgeDocumentMapper docs = mock(BotKnowledgeDocumentMapper.class);
        when(docs.selectById(1L)).thenReturn(null);
        KnowledgeMigrationSnapshotService service = new KnowledgeMigrationSnapshotService(
            docs, mock(BotKnowledgeChunkMapper.class), mock(BotKnowledgeMigrationJobMapper.class),
            mock(KnowledgeDocumentReleaseService.class));
        KnowledgeMigrationSnapshotService.SnapshotException error = assertThrows(
            KnowledgeMigrationSnapshotService.SnapshotException.class, () -> service.create(1L, 2L));
        assertEquals(404, error.status());
    }

    @Test
    void rejectsDraftSource() {
        BotKnowledgeDocument source = new BotKnowledgeDocument();
        source.setId(1L); source.setStatus(2); source.setSourceScope("KNOWLEDGE"); source.setPublishStatus("DRAFT");
        BotKnowledgeDocumentMapper docs = mock(BotKnowledgeDocumentMapper.class);
        when(docs.selectById(1L)).thenReturn(source);
        KnowledgeMigrationSnapshotService service = new KnowledgeMigrationSnapshotService(
            docs, mock(BotKnowledgeChunkMapper.class), mock(BotKnowledgeMigrationJobMapper.class),
            mock(KnowledgeDocumentReleaseService.class));
        KnowledgeMigrationSnapshotService.SnapshotException error = assertThrows(
            KnowledgeMigrationSnapshotService.SnapshotException.class, () -> service.create(1L, 2L));
        assertEquals(409, error.status());
    }

    @Test
    void reusesExistingHashBeforeAllocatingVersion() {
        BotKnowledgeDocument source = new BotKnowledgeDocument();
        source.setId(1L); source.setStatus(2); source.setSourceScope("KNOWLEDGE"); source.setPublishStatus("PUBLISHED"); source.setKnowledgeSetKey("k");
        BotKnowledgeChunk chunk = new BotKnowledgeChunk();
        chunk.setId(10L); chunk.setDocumentId(1L); chunk.setChunkIndex(0); chunk.setContent("hello");
        BotKnowledgeMigrationJob existing = new BotKnowledgeMigrationJob();
        existing.setId(9L); existing.setSourceDocumentId(1L); existing.setTargetDocumentId(7L); existing.setTargetVersionId(3L);
        BotKnowledgeDocumentMapper docs = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeChunkMapper chunks = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeMigrationJobMapper jobs = mock(BotKnowledgeMigrationJobMapper.class);
        KnowledgeDocumentReleaseService release = mock(KnowledgeDocumentReleaseService.class);
        when(docs.selectById(1L)).thenReturn(source);
        when(chunks.selectList(any())).thenReturn(List.of(chunk));
        when(jobs.selectOne(any())).thenReturn(existing);
        KnowledgeMigrationSnapshotService.SnapshotResult result = new KnowledgeMigrationSnapshotService(
            docs, chunks, jobs, release).create(1L, 2L);
        assertEquals(9L, result.jobId());
        verify(release, never()).nextVersion(anyString());
        verify(docs, never()).insert(any(BotKnowledgeDocument.class));
    }
}
