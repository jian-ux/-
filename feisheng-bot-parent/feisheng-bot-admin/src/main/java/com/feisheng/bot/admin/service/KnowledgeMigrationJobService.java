package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotKnowledgeMigrationJob;
import com.feisheng.bot.admin.mapper.BotKnowledgeMigrationJobMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Creates, observes, retries, and queues migration jobs. */
@Service
public class KnowledgeMigrationJobService {
    private final KnowledgeMigrationSnapshotService snapshotService;
    private final BotKnowledgeMigrationJobMapper jobMapper;
    private final KnowledgeMigrationWorker worker;
    private final Executor executor;
    private final int maxRetries;

    public KnowledgeMigrationJobService(
            KnowledgeMigrationSnapshotService snapshotService,
            BotKnowledgeMigrationJobMapper jobMapper,
            KnowledgeMigrationWorker worker,
            @Qualifier("knowledgeMigrationExecutor") Executor executor,
            @Value("${knowledge.migration.retry-limit:3}") int maxRetries) {
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.maxRetries = Math.max(0, maxRetries);
    }

    public MigrationJobView create(Long sourceDocumentId, Long operatorId) {
        KnowledgeMigrationSnapshotService.SnapshotResult snapshot =
            snapshotService.create(sourceDocumentId, operatorId);
        enqueue(snapshot.jobId());
        return get(snapshot.jobId());
    }

    public MigrationJobView get(Long jobId) {
        BotKnowledgeMigrationJob job = jobMapper.selectById(jobId);
        if (job == null) throw new MigrationJobException(404, "迁移任务不存在");
        return MigrationJobView.from(job);
    }

    public MigrationJobView retry(Long jobId, Long operatorId) {
        BotKnowledgeMigrationJob job = jobMapper.selectById(jobId);
        if (job == null) throw new MigrationJobException(404, "迁移任务不存在");
        if (!"FAILED".equals(job.getStatus())) {
            throw new MigrationJobException(409, "只有失败任务可以重试");
        }
        int retries = job.getRetryCount() == null ? 0 : job.getRetryCount();
        int limit = job.getMaxRetries() == null ? maxRetries : job.getMaxRetries();
        if (retries >= limit) throw new MigrationJobException(409, "已达到迁移重试上限");
        job.setRetryCount(retries + 1);
        job.setStatus(retryStatus(job.getCurrentStep()));
        job.setErrorMessage(null);
        job.setNextRetryAt(null);
        job.setLeaseOwner(null);
        job.setLeaseUntil(null);
        job.setReviewerId(operatorId);
        job.setUpdatedAt(new Date());
        jobMapper.updateById(job);
        enqueue(job.getId());
        return MigrationJobView.from(job);
    }

    private String retryStatus(String step) {
        if (step == null || step.isBlank()) return KnowledgeMigrationStatus.PENDING.name();
        try {
            KnowledgeMigrationStatus status = KnowledgeMigrationStatus.valueOf(step);
            return status == KnowledgeMigrationStatus.REVIEW_REQUIRED
                ? KnowledgeMigrationStatus.PENDING.name() : status.name();
        } catch (IllegalArgumentException ignored) {
            return KnowledgeMigrationStatus.PENDING.name();
        }
    }

    private void enqueue(Long jobId) {
        if (jobId == null) throw new MigrationJobException(500, "迁移任务 ID 为空");
        try {
            executor.execute(() -> worker.run(jobId));
        } catch (RejectedExecutionException e) {
            BotKnowledgeMigrationJob job = jobMapper.selectById(jobId);
            if (job != null) {
                job.setErrorMessage("迁移队列已满，请稍后重试");
                jobMapper.markQueueRejected(job.getId(), job.getStatus(),
                    job.getLockVersion() == null ? 0L : job.getLockVersion(), job.getErrorMessage());
            }
            throw new MigrationJobException(503, "迁移队列已满");
        }
    }

    public record MigrationJobView(Long id, Long sourceDocumentId, Long targetDocumentId,
                                   String knowledgeSetKey, String status, String currentStep,
                                   int totalUnits, int processedUnits, int conflictUnits,
                                   int approvedUnits, int retryCount, String lastError) {
        static MigrationJobView from(BotKnowledgeMigrationJob job) {
            return new MigrationJobView(job.getId(), job.getSourceDocumentId(),
                job.getTargetDocumentId(), job.getKnowledgeSetKey(), job.getStatus(),
                job.getCurrentStep(), value(job.getTotalUnits()), value(job.getProcessedUnits()),
                value(job.getConflictUnits()), value(job.getApprovedUnits()),
                value(job.getRetryCount()), job.getErrorMessage());
        }
        private static int value(Integer value) { return value == null ? 0 : value; }
    }

    public static class MigrationJobException extends RuntimeException {
        private final int status;
        public MigrationJobException(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }
}
