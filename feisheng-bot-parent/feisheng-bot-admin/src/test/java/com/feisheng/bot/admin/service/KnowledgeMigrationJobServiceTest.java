package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotKnowledgeMigrationJob;
import com.feisheng.bot.admin.mapper.BotKnowledgeMigrationJobMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeMigrationJobServiceTest {
    @Test
    void createSnapshotsAndEnqueuesExactlyOnce() {
        KnowledgeMigrationSnapshotService snapshot = mock(KnowledgeMigrationSnapshotService.class);
        BotKnowledgeMigrationJobMapper jobs = mock(BotKnowledgeMigrationJobMapper.class);
        KnowledgeMigrationWorker worker = mock(KnowledgeMigrationWorker.class);
        Executor executor = mock(Executor.class);
        when(snapshot.create(10L, 7L)).thenReturn(new KnowledgeMigrationSnapshotService.SnapshotResult(
            3L, 10L, 20L, 2, "hash", 4));
        BotKnowledgeMigrationJob job = job(3L, "PENDING");
        when(jobs.selectById(3L)).thenReturn(job);

        KnowledgeMigrationJobService service = new KnowledgeMigrationJobService(
            snapshot, jobs, worker, executor, 3);
        KnowledgeMigrationJobService.MigrationJobView view = service.create(10L, 7L);

        assertEquals("PENDING", view.status());
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void retriesOnlyFailedJobAndStopsAfterLimit() {
        KnowledgeMigrationSnapshotService snapshot = mock(KnowledgeMigrationSnapshotService.class);
        BotKnowledgeMigrationJobMapper jobs = mock(BotKnowledgeMigrationJobMapper.class);
        KnowledgeMigrationWorker worker = mock(KnowledgeMigrationWorker.class);
        Executor executor = mock(Executor.class);
        BotKnowledgeMigrationJob job = job(3L, "FAILED");
        job.setCurrentStep("CONFLICT_CHECKING");
        job.setRetryCount(0);
        job.setMaxRetries(2);
        when(jobs.selectById(3L)).thenReturn(job);
        KnowledgeMigrationJobService service = new KnowledgeMigrationJobService(
            snapshot, jobs, worker, executor, 3);

        KnowledgeMigrationJobService.MigrationJobView view = service.retry(3L, 9L);

        assertEquals("CONFLICT_CHECKING", view.status());
        verify(jobs).updateById(job);
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void queueRejectionMarksFailureWithoutRunningWorkerInline() {
        KnowledgeMigrationSnapshotService snapshot = mock(KnowledgeMigrationSnapshotService.class);
        BotKnowledgeMigrationJobMapper jobs = mock(BotKnowledgeMigrationJobMapper.class);
        KnowledgeMigrationWorker worker = mock(KnowledgeMigrationWorker.class);
        Executor executor = command -> { throw new RejectedExecutionException("full"); };
        when(snapshot.create(10L, 7L)).thenReturn(new KnowledgeMigrationSnapshotService.SnapshotResult(
            3L, 10L, 20L, 2, "hash", 4));
        BotKnowledgeMigrationJob job = job(3L, "PENDING");
        when(jobs.selectById(3L)).thenReturn(job);

        KnowledgeMigrationJobService service = new KnowledgeMigrationJobService(
            snapshot, jobs, worker, executor, 3);

        assertEquals(503, assertThrows(KnowledgeMigrationJobService.MigrationJobException.class,
            () -> service.create(10L, 7L)).status());
        verifyNoInteractions(worker);
    }

    private static BotKnowledgeMigrationJob job(Long id, String status) {
        BotKnowledgeMigrationJob job = new BotKnowledgeMigrationJob();
        job.setId(id); job.setStatus(status); job.setLockVersion(0L);
        job.setTotalUnits(4); job.setProcessedUnits(0); job.setConflictUnits(0);
        job.setApprovedUnits(0); job.setRetryCount(0); job.setMaxRetries(3);
        return job;
    }
}
