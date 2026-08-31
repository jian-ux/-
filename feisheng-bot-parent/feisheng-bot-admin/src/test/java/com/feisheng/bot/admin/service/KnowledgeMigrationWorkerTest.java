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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
        when(jobs.transitionOwned(any(), any(), any(), any(), anyString(), anyLong())).thenReturn(1);
        when(jobs.updateProgressOwned(any(), anyString(), anyLong(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(1);
        when(jobs.renewLease(any(), anyString(), any(), anyLong())).thenReturn(1);
        when(snapshot.cloneTarget(eq(1L), anyString(), anyLong())).thenReturn(new KnowledgeMigrationSnapshotService.SnapshotResult(
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

        verify(jobs, times(4)).transitionOwned(any(), any(), any(), any(), anyString(), anyLong());
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

    @Test
    void recordsQueueWaitOnlyAfterAWorkerClaimsTheJob() {
        BotKnowledgeMigrationJobMapper jobs = mock(BotKnowledgeMigrationJobMapper.class);
        BotKnowledgeMigrationJob job = job(1L, 10L, 20L);
        job.setUpdatedAt(new java.util.Date(System.currentTimeMillis() - 1000L));
        when(jobs.selectById(1L)).thenReturn(job);
        when(jobs.claim(any(), any(), any(), any(), anyLong())).thenReturn(0);
        KnowledgeMigrationObservability observability = mock(KnowledgeMigrationObservability.class);

        new KnowledgeMigrationWorker(jobs, mock(BotKnowledgeDocumentMapper.class),
            mock(BotKnowledgeChunkMapper.class), mock(KnowledgeMigrationSnapshotService.class),
            mock(StructuredKnowledgeExtractionService.class), mock(FactConflictService.class),
            60_000L, null, observability).run(1L);

        verify(observability, never()).queueWait(any(), any(), any(), any(), anyLong());
    }

    @Test
    void recordsQueueWaitWhenAWorkerClaimsTheJob() {
        BotKnowledgeMigrationJobMapper jobs = mock(BotKnowledgeMigrationJobMapper.class);
        BotKnowledgeMigrationJob job = job(1L, 10L, 20L);
        java.util.Date queuedAt = new java.util.Date(System.currentTimeMillis() - 1_000L);
        job.setUpdatedAt(queuedAt);
        when(jobs.selectById(1L)).thenReturn(job);
        when(jobs.claim(any(), any(), any(), any(), anyLong())).thenReturn(1);
        when(jobs.transitionOwned(any(), any(), any(), any(), anyString(), anyLong())).thenReturn(0);
        KnowledgeMigrationObservability observability = mock(KnowledgeMigrationObservability.class);

        new KnowledgeMigrationWorker(jobs, mock(BotKnowledgeDocumentMapper.class),
            mock(BotKnowledgeChunkMapper.class), mock(KnowledgeMigrationSnapshotService.class),
            mock(StructuredKnowledgeExtractionService.class), mock(FactConflictService.class),
            60_000L, null, observability).run(1L);

        verify(observability).queueWait(eq(1L), eq("set"), eq(1L), eq(2L),
            longThat(elapsed -> elapsed >= 0L));
    }

    @Test
    void expiredRunningLeaseResumesFromPersistedStep() {
        BotKnowledgeMigrationJobMapper jobs = mock(BotKnowledgeMigrationJobMapper.class);
        BotKnowledgeMigrationJob job = job(1L, 10L, 20L);
        job.setStatus("RUNNING");
        job.setCurrentStep("CONFLICT_CHECKING");
        job.setLockVersion(4L);
        when(jobs.selectById(1L)).thenReturn(job);
        when(jobs.claim(any(), eq("RUNNING"), anyString(), any(), eq(4L))).thenReturn(1);
        when(jobs.transitionOwned(any(), eq("RUNNING"), eq("CONFLICT_CHECKING"), anyString(), anyString(), anyLong())).thenReturn(1);
        when(jobs.updateProgressOwned(any(), anyString(), anyLong(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(1);
        when(jobs.renewLease(any(), anyString(), any(), anyLong())).thenReturn(1);
        FactConflictService conflicts = mock(FactConflictService.class);
        when(conflicts.check(1L, 10L, 20L)).thenReturn(new FactConflictService.ConflictReport(0, 0, 0, 0, 0, 0));
        new KnowledgeMigrationWorker(jobs, mock(BotKnowledgeDocumentMapper.class),
            mock(BotKnowledgeChunkMapper.class), mock(KnowledgeMigrationSnapshotService.class),
            mock(StructuredKnowledgeExtractionService.class), conflicts, 60_000L, null).run(1L);

        verify(jobs, never()).claim(any(), eq("PENDING"), anyString(), any(), anyLong());
        verify(conflicts).check(1L, 10L, 20L);
    }

    @Test
    void failedWorkerPreservesFailedStepAndDoesNotOverwriteAfterLeaseLoss() {
        BotKnowledgeMigrationJobMapper jobs = mock(BotKnowledgeMigrationJobMapper.class);
        BotKnowledgeMigrationJob job = job(1L, 10L, 20L);
        when(jobs.selectById(1L)).thenReturn(job);
        when(jobs.claim(any(), any(), any(), any(), anyLong())).thenReturn(1);
        when(jobs.transitionOwned(any(), any(), any(), any(), anyString(), anyLong())).thenReturn(1);
        when(jobs.updateProgressOwned(any(), anyString(), anyLong(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(0);

        new KnowledgeMigrationWorker(jobs, mock(BotKnowledgeDocumentMapper.class),
            mock(BotKnowledgeChunkMapper.class), mock(KnowledgeMigrationSnapshotService.class),
            mock(StructuredKnowledgeExtractionService.class), mock(FactConflictService.class), 60_000L, null).run(1L);

        verify(jobs, never()).updateById(any(BotKnowledgeMigrationJob.class));
        verify(jobs).failOwned(any(), anyString(), anyLong(), eq("FAILED"), eq("EXTRACTING"), anyString());
    }

    @Test
    void leaseRenewFailureStopsBeforeNextStage() {
        BotKnowledgeMigrationJobMapper jobs = mock(BotKnowledgeMigrationJobMapper.class);
        BotKnowledgeDocumentMapper documents = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeChunkMapper chunks = mock(BotKnowledgeChunkMapper.class);
        KnowledgeMigrationSnapshotService snapshot = mock(KnowledgeMigrationSnapshotService.class);
        StructuredKnowledgeExtractionService extraction = mock(StructuredKnowledgeExtractionService.class);
        BotKnowledgeMigrationJob job = job(1L, 10L, 20L);
        when(jobs.selectById(1L)).thenReturn(job);
        when(jobs.claim(any(), any(), any(), any(), anyLong())).thenReturn(1);
        when(jobs.transitionOwned(any(), any(), any(), any(), anyString(), anyLong())).thenReturn(1);
        when(jobs.updateProgressOwned(any(), anyString(), anyLong(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(1);
        when(jobs.renewLease(any(), anyString(), any(), anyLong())).thenReturn(0);
        when(snapshot.cloneTarget(eq(1L), anyString(), anyLong())).thenReturn(new KnowledgeMigrationSnapshotService.SnapshotResult(
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

        FactConflictService conflicts = mock(FactConflictService.class);
        new KnowledgeMigrationWorker(jobs, mock(BotKnowledgeDocumentMapper.class),
            mock(BotKnowledgeChunkMapper.class), snapshot, extraction, conflicts, 60_000L, null).run(1L);

        verifyNoInteractions(conflicts);
        verify(jobs).failOwned(any(), anyString(), anyLong(), eq("FAILED"), eq("EXTRACTING"), anyString());
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
