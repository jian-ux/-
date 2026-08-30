package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.entity.BotKnowledgeMigrationJob;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeMigrationJobMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeMigrationWorkerTest {
    @Test
    void advancesOneClaimedJobWithoutPublishing() {
        BotKnowledgeMigrationJobMapper jobs = mock(BotKnowledgeMigrationJobMapper.class);
        BotKnowledgeDocumentMapper documents = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeChunkMapper chunks = mock(BotKnowledgeChunkMapper.class);
        KnowledgeMigrationSnapshotService snapshot = mock(KnowledgeMigrationSnapshotService.class);
        StructuredKnowledgeExtractionService extraction = mock(StructuredKnowledgeExtractionService.class);
        FactConflictService conflicts = mock(FactConflictService.class);
        BotKnowledgeMigrationJob job = job(1L, 10L, 20L);
        when(jobs.selectById(1L)).thenReturn(job);
        when(jobs.claim(any(), any(), any(), any(), anyLong())).thenReturn(1);
        when(jobs.transition(any(), any(), any(), any(), anyLong())).thenReturn(1);
        when(snapshot.cloneTarget(1L)).thenReturn(new KnowledgeMigrationSnapshotService.SnapshotResult(
            1L, 10L, 20L, 2, "hash", 1));
        BotKnowledgeDocument target = new BotKnowledgeDocument();
        target.setId(20L); target.setStatus(2); target.setPublishStatus("DRAFT");
        when(documents.selectById(20L)).thenReturn(target);
        BotKnowledgeChunk chunk = new BotKnowledgeChunk(); chunk.setId(5L); chunk.setDocumentId(20L); chunk.setContent("source");
        when(chunks.selectList(any())).thenReturn(List.of(chunk));
        StructuredKnowledgeExtractionService.ExtractionReport report =
            new StructuredKnowledgeExtractionService.ExtractionReport(20L, "SUCCESS", "ok", "hash", "model",
                1, 1, 1, 0, 1, 1,
                new StructuredKnowledgeExtractionService.PersistSummary(1, 1, 0, 0, 0, List.of(1L)),
                List.of(), List.of());
        when(extraction.extractChunks(any(), any(), any())).thenReturn(report);
        when(conflicts.check(1L, 10L, 20L)).thenReturn(new FactConflictService.ConflictReport(1, 0, 0, 0, 0, 0));

        new KnowledgeMigrationWorker(jobs, documents, chunks, snapshot, extraction, conflicts,
            60_000L, null).run(1L);

        verify(jobs, times(4)).transition(any(), any(), any(), any(), anyLong());
        verify(conflicts).check(1L, 10L, 20L);
        verify(documents, never()).publishDraftGuarded(any(), any(), any());
    }

    @Test
    void duplicateQueueDeliveryDoesNothingWhenClaimFails() {
        BotKnowledgeMigrationJobMapper jobs = mock(BotKnowledgeMigrationJobMapper.class);
        BotKnowledgeMigrationJob job = job(1L, 10L, 20L);
        when(jobs.selectById(1L)).thenReturn(job);
        when(jobs.claim(any(), any(), any(), any(), anyLong())).thenReturn(0);
        StructuredKnowledgeExtractionService extraction = mock(StructuredKnowledgeExtractionService.class);
        new KnowledgeMigrationWorker(jobs, mock(BotKnowledgeDocumentMapper.class),
            mock(BotKnowledgeChunkMapper.class), mock(KnowledgeMigrationSnapshotService.class),
            extraction, mock(FactConflictService.class), 60_000L, null).run(1L);
        verifyNoInteractions(extraction);
    }

    private static BotKnowledgeMigrationJob job(Long id, Long source, Long target) {
        BotKnowledgeMigrationJob job = new BotKnowledgeMigrationJob();
        job.setId(id); job.setSourceDocumentId(source); job.setTargetDocumentId(target);
        job.setSourceVersionId(1L); job.setTargetVersionId(2L); job.setKnowledgeSetKey("set");
        job.setStatus("PENDING"); job.setCurrentStep("SNAPSHOT"); job.setLockVersion(0L);
        job.setRetryCount(0); job.setMaxRetries(3);
        return job;
    }
}
