package com.feisheng.bot.admin.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** Emits migration audit logs and records operational metrics when Actuator is available. */
@Service
public class KnowledgeMigrationObservability {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeMigrationObservability.class);
    private final MeterRegistry registry;

    @Autowired
    public KnowledgeMigrationObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public void transition(Long jobId, String knowledgeSetKey, Long sourceVersion, Long targetVersion,
                           String from, String to, long elapsedMillis) {
        log.info("migration_event=transition migrationJobId={} knowledgeSetKey={} sourceVersion={} targetVersion={} from={} to={}",
            jobId, knowledgeSetKey, sourceVersion, targetVersion, from, to);
        if (registry != null) {
            Timer.builder("knowledge.migration.stage.duration")
                .tag("stage", to == null ? "UNKNOWN" : to).register(registry)
                .record(Duration.ofMillis(Math.max(0L, elapsedMillis)));
        }
    }

    public void queueWait(Long jobId, String knowledgeSetKey, Long sourceVersion, Long targetVersion,
                          long elapsedMillis) {
        long safeElapsedMillis = Math.max(0L, elapsedMillis);
        log.info("migration_event=queue_wait migrationJobId={} knowledgeSetKey={} sourceVersion={} targetVersion={} elapsedMillis={}",
            jobId, knowledgeSetKey, sourceVersion, targetVersion, safeElapsedMillis);
        if (registry != null) {
            Timer.builder("knowledge.migration.queue.wait")
                .register(registry)
                .record(Duration.ofMillis(safeElapsedMillis));
        }
    }

    public void failure(Long jobId, String knowledgeSetKey, Long sourceVersion, Long targetVersion,
                        String stage, String kind) {
        log.warn("migration_event=failure migrationJobId={} knowledgeSetKey={} sourceVersion={} targetVersion={} stage={} kind={}",
            jobId, knowledgeSetKey, sourceVersion, targetVersion, stage, kind);
        increment("knowledge.migration.failures", 1.0d, "kind", kind == null ? "unknown" : kind);
    }

    public void conflicts(Long jobId, String knowledgeSetKey, Long sourceVersion, Long targetVersion,
                          int candidates, int conflicts, int unknown) {
        log.info("migration_event=conflict_summary migrationJobId={} knowledgeSetKey={} sourceVersion={} targetVersion={} candidates={} conflicts={} unknown={}",
            jobId, knowledgeSetKey, sourceVersion, targetVersion, candidates, conflicts, unknown);
        increment("knowledge.migration.candidates", candidates);
        increment("knowledge.migration.conflicts", conflicts);
        increment("knowledge.migration.unknown_judgments", unknown);
    }

    public void review(Long jobId, String knowledgeSetKey, Long sourceVersion, Long targetVersion,
                       Long operatorId, String action, long elapsedMillis) {
        log.info("migration_event=review migrationJobId={} knowledgeSetKey={} sourceVersion={} targetVersion={} operatorId={} action={}",
            jobId, knowledgeSetKey, sourceVersion, targetVersion, operatorId, action);
        if (registry != null) {
            Timer.builder("knowledge.migration.review.duration").tag("action", action)
                .register(registry).record(Duration.ofMillis(Math.max(0L, elapsedMillis)));
        }
    }

    public void shadowIndexFailure(Long jobId, String knowledgeSetKey, Long sourceVersion, Long targetVersion) {
        failure(jobId, knowledgeSetKey, sourceVersion, targetVersion, "SHADOW_INDEX", "shadow_index");
    }

    public void release(Long jobId, String knowledgeSetKey, Long sourceVersion, Long targetVersion,
                        boolean success, boolean rollback) {
        String action = rollback ? "rollback" : "switch";
        log.info("migration_event={} migrationJobId={} knowledgeSetKey={} sourceVersion={} targetVersion={} success={}",
            action, jobId, knowledgeSetKey, sourceVersion, targetVersion, success);
        if (rollback) increment("knowledge.migration.rollbacks", 1.0d);
        else if (success) increment("knowledge.migration.switch.success", 1.0d);
        else increment("knowledge.migration.failures", 1.0d, "kind", "switch");
    }

    private void increment(String name, double amount, String... tags) {
        if (registry != null && amount > 0.0d) registry.counter(name, tags).increment(amount);
    }
}
