package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.entity.BotKnowledgeMigrationJob;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeMigrationJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Executes one migration job at a time; it never publishes a document. */
@Service
public class KnowledgeMigrationWorker {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeMigrationWorker.class);
    private final BotKnowledgeMigrationJobMapper jobMapper;
    private final BotKnowledgeDocumentMapper documentMapper;
    private final BotKnowledgeChunkMapper chunkMapper;
    private final KnowledgeMigrationSnapshotService snapshotService;
    private final StructuredKnowledgeExtractionService extractionService;
    private final FactConflictService conflictService;
    private final KnowledgeMigrationObservability observability;
    private final long leaseMillis;
    private final Long preferredModelId;

    public KnowledgeMigrationWorker(BotKnowledgeMigrationJobMapper jobMapper,
                                    BotKnowledgeDocumentMapper documentMapper,
                                    BotKnowledgeChunkMapper chunkMapper,
                                    KnowledgeMigrationSnapshotService snapshotService,
                                    StructuredKnowledgeExtractionService extractionService,
                                    FactConflictService conflictService,
                                    @Value("${knowledge.migration.lease-duration-ms:300000}") long leaseMillis,
                                    @Value("${knowledge.migration.preferred-model-id:0}") Long preferredModelId) {
        this(jobMapper, documentMapper, chunkMapper, snapshotService, extractionService, conflictService,
            leaseMillis, preferredModelId, new KnowledgeMigrationObservability(null));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeMigrationWorker(BotKnowledgeMigrationJobMapper jobMapper,
                                    BotKnowledgeDocumentMapper documentMapper,
                                    BotKnowledgeChunkMapper chunkMapper,
                                    KnowledgeMigrationSnapshotService snapshotService,
                                    StructuredKnowledgeExtractionService extractionService,
                                    FactConflictService conflictService,
                                    @Value("${knowledge.migration.lease-duration-ms:300000}") long leaseMillis,
                                    @Value("${knowledge.migration.preferred-model-id:0}") Long preferredModelId,
                                    KnowledgeMigrationObservability observability) {
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper");
        this.documentMapper = Objects.requireNonNull(documentMapper, "documentMapper");
        this.chunkMapper = Objects.requireNonNull(chunkMapper, "chunkMapper");
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
        this.extractionService = Objects.requireNonNull(extractionService, "extractionService");
        this.conflictService = Objects.requireNonNull(conflictService, "conflictService");
        this.observability = Objects.requireNonNull(observability, "observability");
        this.leaseMillis = Math.max(1000L, leaseMillis);
        this.preferredModelId = preferredModelId == null || preferredModelId <= 0 ? null : preferredModelId;
    }

    public void run(Long jobId) {
        if (jobId == null) return;
        BotKnowledgeMigrationJob job = jobMapper.selectById(jobId);
        if (job == null || terminal(job.getStatus())
                || KnowledgeMigrationStatus.REVIEW_REQUIRED.name().equals(job.getStatus())
                || KnowledgeMigrationStatus.READY_TO_SWITCH.name().equals(job.getStatus())) return;

        String startingStatus = Objects.toString(job.getStatus(), "PENDING");
        String startingStep = Objects.toString(job.getCurrentStep(), startingStatus);
        long version = value(job.getLockVersion());
        String owner = "migration-worker-" + UUID.randomUUID();
        int claimed = jobMapper.claim(jobId, Objects.toString(job.getStatus(), "PENDING"), owner,
            new Date(System.currentTimeMillis() + leaseMillis), version);
        if (claimed != 1) return;
        long queueWaitMillis = job.getUpdatedAt() == null ? 0L
            : Math.max(0L, System.currentTimeMillis() - job.getUpdatedAt().getTime());
        observability.queueWait(job.getId(), job.getKnowledgeSetKey(), job.getSourceVersionId(),
            job.getTargetVersionId(), queueWaitMillis);
        job.setLeaseOwner(owner);
        job.setLeaseUntil(new Date(System.currentTimeMillis() + leaseMillis));
        job.setStatus("RUNNING");
        job.setLockVersion(version + 1);
        try {
            StructuredKnowledgeExtractionService.ExtractionReport report = null;
            boolean extractionRequired = startingStatus.equals(KnowledgeMigrationStatus.PENDING.name())
                    || startingStatus.equals(KnowledgeMigrationStatus.EXTRACTING.name())
                    || startingStatus.equals(KnowledgeMigrationStatus.EMBEDDING.name())
                    || startingStep.equals("SNAPSHOT")
                    || startingStep.equals(KnowledgeMigrationStatus.EXTRACTING.name())
                    || startingStep.equals(KnowledgeMigrationStatus.EMBEDDING.name());
            if (extractionRequired) {
                advance(job, KnowledgeMigrationStatus.EXTRACTING);
                KnowledgeMigrationSnapshotService.SnapshotResult snapshot = snapshotService.cloneTarget(
                    jobId, job.getLeaseOwner(), job.getLockVersion());
                if (snapshot.targetDocumentId() != null && !Objects.equals(snapshot.targetDocumentId(), job.getTargetDocumentId())) {
                    job.setTargetDocumentId(snapshot.targetDocumentId());
                }
                if (snapshot.targetDocumentId() != null) job.setTargetDocumentId(snapshot.targetDocumentId());
                BotKnowledgeDocument target = documentMapper.selectById(job.getTargetDocumentId());
                if (target == null) throw new MigrationFailure("目标草稿不存在");
                List<BotKnowledgeChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<BotKnowledgeChunk>()
                    .eq(BotKnowledgeChunk::getDocumentId, target.getId())
                    .orderByAsc(BotKnowledgeChunk::getChunkIndex));
                report = extractionService.extractChunks(target, chunks, preferredModelId);
                if (!"SUCCESS".equals(report.status()) || report.failedBatches() > 0) {
                    throw new MigrationFailure("结构化抽取未完整成功: " + report.message());
                }
                job.setTotalUnits(report.sourceChunks());
                job.setProcessedUnits(report.sourceChunks());
                persist(job);
                renew(job);
                advance(job, KnowledgeMigrationStatus.EMBEDDING);
            }
            if (report != null && report.validatedUnits() != report.embeddedUnits()) {
                throw new MigrationFailure("结构化单元向量不完整");
            }
            if (!KnowledgeMigrationStatus.CONFLICT_CHECKING.name().equals(job.getStatus())) {
                advance(job, KnowledgeMigrationStatus.CONFLICT_CHECKING);
            }
            FactConflictService.ConflictReport conflicts = conflictService.check(
                job.getId(), job.getSourceDocumentId(), job.getTargetDocumentId());
            observability.conflicts(job.getId(), job.getKnowledgeSetKey(), job.getSourceVersionId(),
                job.getTargetVersionId(), conflicts.candidatePairs(),
                conflicts.blocking() + conflicts.warning() + conflicts.info(), conflicts.unknown());
            job.setConflictUnits(conflicts.blocking() + conflicts.warning());
            persist(job);
            advance(job, KnowledgeMigrationStatus.REVIEW_REQUIRED);
            log.info("migrationJobId={} knowledgeSetKey={} sourceVersion={} targetVersion={} step={}",
                job.getId(), job.getKnowledgeSetKey(), job.getSourceVersionId(),
                job.getTargetVersionId(), KnowledgeMigrationStatus.REVIEW_REQUIRED);
        } catch (KnowledgeMigrationSnapshotService.SnapshotException e) {
            if (e.status() == 409 && e.getMessage() != null && e.getMessage().contains("变化")) {
                fail(job, KnowledgeMigrationStatus.STALE, e.getMessage());
            } else {
                fail(job, KnowledgeMigrationStatus.FAILED, e.getMessage());
            }
        } catch (Exception e) {
            fail(job, KnowledgeMigrationStatus.FAILED,
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void advance(BotKnowledgeMigrationJob job, KnowledgeMigrationStatus next) {
        String expected = job.getStatus();
        long version = value(job.getLockVersion());
        int changed = jobMapper.transitionOwned(job.getId(), expected, next.name(), next.name(),
            job.getLeaseOwner(), version);
        if (changed != 1) throw new MigrationFailure("任务已被其他 worker 接管");
        job.setStatus(next.name());
        job.setCurrentStep(next.name());
        job.setLockVersion(version + 1);
        long elapsedMillis = job.getUpdatedAt() == null ? 0L
            : Math.max(0L, System.currentTimeMillis() - job.getUpdatedAt().getTime());
        job.setUpdatedAt(new Date());
        observability.transition(job.getId(), job.getKnowledgeSetKey(), job.getSourceVersionId(),
            job.getTargetVersionId(), expected, next.name(), elapsedMillis);
        log.info("migrationJobId={} knowledgeSetKey={} sourceVersion={} targetVersion={} step={}",
            job.getId(), job.getKnowledgeSetKey(), job.getSourceVersionId(),
            job.getTargetVersionId(), next);
    }

    private void persist(BotKnowledgeMigrationJob job) {
        long version = value(job.getLockVersion());
        int changed = jobMapper.updateProgressOwned(job.getId(), job.getLeaseOwner(), version,
            job.getTargetDocumentId(), job.getTotalUnits(), job.getProcessedUnits(),
            job.getConflictUnits(), job.getApprovedUnits(), job.getErrorMessage(), job.getLeaseUntil());
        if (changed != 1) throw new MigrationFailure("任务已被其他 worker 接管");
        job.setLockVersion(version + 1);
        job.setUpdatedAt(new Date());
    }

    private void renew(BotKnowledgeMigrationJob job) {
        if (job.getLeaseOwner() == null) return;
        int changed = jobMapper.renewLease(job.getId(), job.getLeaseOwner(),
            new Date(System.currentTimeMillis() + leaseMillis), value(job.getLockVersion()));
        if (changed != 1) throw new MigrationFailure("租约续期失败，任务已被其他 worker 接管");
        job.setLeaseUntil(new Date(System.currentTimeMillis() + leaseMillis));
    }

    private void fail(BotKnowledgeMigrationJob job, KnowledgeMigrationStatus status, String message) {
        String failedStep = Objects.toString(job.getCurrentStep(), Objects.toString(job.getStatus(), "UNKNOWN"));
        int changed = jobMapper.failOwned(job.getId(), job.getLeaseOwner(), value(job.getLockVersion()),
            status.name(), failedStep, message);
        if (changed == 1) {
            job.setStatus(status.name());
            job.setCurrentStep(failedStep);
            job.setErrorMessage(message);
            job.setLeaseUntil(null);
            job.setLeaseOwner(null);
            job.setLockVersion(value(job.getLockVersion()) + 1);
        }
        log.warn("migrationJobId={} knowledgeSetKey={} sourceVersion={} targetVersion={} step={} error={}",
            job.getId(), job.getKnowledgeSetKey(), job.getSourceVersionId(),
            job.getTargetVersionId(), status, message);
        observability.failure(job.getId(), job.getKnowledgeSetKey(), job.getSourceVersionId(),
            job.getTargetVersionId(), failedStep, failureKind(failedStep));
    }

    private static String failureKind(String step) {
        if (KnowledgeMigrationStatus.EXTRACTING.name().equals(step)) return "extraction";
        if (KnowledgeMigrationStatus.EMBEDDING.name().equals(step)) return "vector";
        if (KnowledgeMigrationStatus.CONFLICT_CHECKING.name().equals(step)) return "model";
        return "worker";
    }

    private boolean terminal(String status) {
        return KnowledgeMigrationStatus.COMPLETED.name().equals(status)
            || KnowledgeMigrationStatus.STALE.name().equals(status);
    }

    private static long value(Long value) { return value == null ? 0L : value; }

    private static class MigrationFailure extends RuntimeException {
        MigrationFailure(String message) { super(message); }
    }
}
