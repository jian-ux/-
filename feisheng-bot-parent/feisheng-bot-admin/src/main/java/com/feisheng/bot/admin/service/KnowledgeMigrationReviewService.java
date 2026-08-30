package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeConflict;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.entity.BotKnowledgeMigrationJob;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeConflictMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeMigrationJobMapper;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeSemanticUnitMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/** Human conflict decisions and the document-level release gate. */
@Service
public class KnowledgeMigrationReviewService {
    private static final String PENDING = "PENDING";
    private final BotKnowledgeMigrationJobMapper jobMapper;
    private final BotKnowledgeConflictMapper conflictMapper;
    private final BotKnowledgeSemanticUnitMapper unitMapper;
    private final BotKnowledgeDocumentMapper documentMapper;
    private final BotKnowledgeChunkMapper chunkMapper;
    private final ObjectMapper objectMapper;

    public KnowledgeMigrationReviewService(BotKnowledgeMigrationJobMapper jobMapper,
                                           BotKnowledgeConflictMapper conflictMapper,
                                           BotKnowledgeSemanticUnitMapper unitMapper,
                                           BotKnowledgeDocumentMapper documentMapper,
                                           BotKnowledgeChunkMapper chunkMapper,
                                           ObjectMapper objectMapper) {
        this.jobMapper = jobMapper;
        this.conflictMapper = conflictMapper;
        this.unitMapper = unitMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.objectMapper = objectMapper;
    }

    public ConflictResolution resolveConflict(Long jobId, Long conflictId,
                                              ResolutionRequest request, Long reviewerId) {
        BotKnowledgeMigrationJob job = requireJob(jobId);
        requireReviewer(reviewerId);
        BotKnowledgeConflict conflict = conflictMapper.selectById(conflictId);
        if (conflict == null || !Objects.equals(jobId, conflict.getMigrationJobId())) {
            throw new ReviewException(404, "冲突不存在或不属于当前迁移任务");
        }
        String resolution = request == null ? null : request.resolution();
        if (request == null || !List.of("ADOPT_TARGET", "KEEP_SOURCE", "MERGE", "SPLIT_SCOPE", "NOT_CONFLICT")
                .contains(resolution)) {
            throw new ReviewException(400, "不支持的冲突处理方式");
        }
        if (!allowedResolution(conflict.getSeverity(), resolution)) {
            throw new ReviewException(409, "当前冲突级别不允许该处理方式");
        }
        if (!PENDING.equals(conflict.getStatus())) {
            throw new ReviewException(409, "冲突已处理，请刷新后重试");
        }
        Date now = new Date();
        String note = request == null ? null : normalize(request.note());
        conflict.setRuleResult(withResolutionAudit(conflict, resolution, note, reviewerId, now));
        conflict.setResolution(resolution);
        conflict.setResolutionNote(note);
        conflict.setStatus("NOT_CONFLICT".equals(resolution) ? "NOT_CONFLICT" : "RESOLVED");
        conflict.setReviewerId(reviewerId);
        conflict.setReviewedAt(now);
        conflict.setUpdatedAt(now);
        conflictMapper.updateById(conflict);
        return new ConflictResolution(job.getId(), conflict.getId(), conflict.getStatus(),
            conflict.getResolution(), reviewerId, now);
    }

    public GateReport confirmDocument(Long jobId, Long reviewerId) {
        BotKnowledgeMigrationJob job = requireJob(jobId);
        requireReviewer(reviewerId);
        if (KnowledgeMigrationStatus.READY_TO_SWITCH.name().equals(job.getStatus())) {
            return passedReport();
        }
        if (!KnowledgeMigrationStatus.REVIEW_REQUIRED.name().equals(job.getStatus())) {
            throw new ReviewException(409, "当前任务尚未进入人工审核阶段");
        }
        List<String> blockers = new ArrayList<>();
        List<BotKnowledgeSemanticUnit> units = unitMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeSemanticUnit>()
                .eq(BotKnowledgeSemanticUnit::getDocumentId, job.getTargetDocumentId())
                .ne(BotKnowledgeSemanticUnit::getDeleted, 1));
        int unreviewed = 0;
        int missingEvidence = 0;
        for (BotKnowledgeSemanticUnit unit : units) {
            if (!"APPROVED".equals(unit.getStatus()) && !"REJECTED".equals(unit.getStatus())) unreviewed++;
            if ("APPROVED".equals(unit.getStatus())
                    && (!hasVector(unit.getEmbedding()) || !validEvidence(unit.getEvidenceChunkIdsJson()))) {
                missingEvidence++;
            }
        }
        if (unreviewed > 0) blockers.add("存在未审核结构化单元: " + unreviewed);
        if (missingEvidence > 0) blockers.add("存在缺少向量或证据的已通过单元: " + missingEvidence);
        List<BotKnowledgeConflict> conflicts = conflictMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeConflict>()
                .eq(BotKnowledgeConflict::getMigrationJobId, jobId)
                .in(BotKnowledgeConflict::getSeverity, List.of("BLOCKING", "WARNING"))
                .eq(BotKnowledgeConflict::getStatus, PENDING));
        int blocking = (int) conflicts.stream().filter(c -> "BLOCKING".equals(c.getSeverity())).count();
        int warning = (int) conflicts.stream().filter(c -> "WARNING".equals(c.getSeverity())).count();
        if (blocking > 0) blockers.add("存在未解决阻断冲突: " + blocking);
        if (warning > 0) blockers.add("存在未解决警告冲突: " + warning);
        if (!sourceHashMatches(job)) blockers.add("源文档内容已变化，任务已过期");
        if (job.getTargetDocumentId() == null) blockers.add("目标文档不存在");
        if (!blockers.isEmpty()) {
            return new GateReport(false, unreviewed, missingEvidence, blocking, warning, List.copyOf(blockers));
        }
        job.setStatus(KnowledgeMigrationStatus.READY_TO_SWITCH.name());
        job.setCurrentStep(KnowledgeMigrationStatus.READY_TO_SWITCH.name());
        job.setReviewerId(reviewerId);
        job.setReviewedAt(new Date());
        jobMapper.updateById(job);
        return passedReport();
    }

    private BotKnowledgeMigrationJob requireJob(Long id) {
        if (id == null) throw new ReviewException(400, "迁移任务 ID 不能为空");
        BotKnowledgeMigrationJob job = jobMapper.selectById(id);
        if (job == null) throw new ReviewException(404, "迁移任务不存在");
        return job;
    }

    private boolean sourceHashMatches(BotKnowledgeMigrationJob job) {
        if (job.getSourceDocumentId() == null || job.getSourceContentHash() == null) return false;
        List<BotKnowledgeChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<BotKnowledgeChunk>()
            .eq(BotKnowledgeChunk::getDocumentId, job.getSourceDocumentId())
            .orderByAsc(BotKnowledgeChunk::getChunkIndex).orderByAsc(BotKnowledgeChunk::getId));
        StringBuilder value = new StringBuilder();
        for (BotKnowledgeChunk chunk : chunks) {
            value.append(chunk.getId()).append('\0')
                .append(Objects.toString(chunk.getChunkIndex(), "")).append('\0')
                .append(chunk.getContent()).append('\1');
        }
        return Objects.equals(job.getSourceContentHash(),
            com.feisheng.bot.common.util.EmbeddingMetadataUtil.contentHash(value.toString()));
    }

    private GateReport passedReport() {
        return new GateReport(true, 0, 0, 0, 0, List.of("人工确认通过"));
    }

    private void requireReviewer(Long reviewerId) {
        if (reviewerId == null || reviewerId <= 0) {
            throw new ReviewException(403, "需要已认证的审核人");
        }
    }

    private boolean allowedResolution(String severity, String resolution) {
        return !"INFO".equals(severity) || "NOT_CONFLICT".equals(resolution);
    }

    private String withResolutionAudit(BotKnowledgeConflict conflict, String resolution,
                                       String reason, Long reviewerId, Date reviewedAt) {
        ObjectNode root = readRuleResult(conflict.getRuleResult());
        ObjectNode audit = root.putObject("resolutionAudit");
        ObjectNode before = audit.putObject("before");
        before.put("status", Objects.toString(conflict.getStatus(), ""));
        before.put("resolution", Objects.toString(conflict.getResolution(), ""));
        before.put("severity", Objects.toString(conflict.getSeverity(), ""));
        ObjectNode after = audit.putObject("after");
        after.put("status", "NOT_CONFLICT".equals(resolution) ? "NOT_CONFLICT" : "RESOLVED");
        after.put("resolution", resolution);
        after.put("reviewerId", reviewerId);
        after.put("reviewedAt", reviewedAt.getTime());
        if (reason == null) audit.putNull("reason"); else audit.put("reason", reason);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ReviewException(500, "无法记录冲突处理审计信息");
        }
    }

    private ObjectNode readRuleResult(String ruleResult) {
        if (ruleResult != null && !ruleResult.isBlank()) {
            try {
                JsonNode parsed = objectMapper.readTree(ruleResult);
                if (parsed instanceof ObjectNode object) return object.deepCopy();
            } catch (Exception ignored) {
                // Keep resolution audit available even when an old rule result was malformed.
            }
        }
        return objectMapper.createObjectNode();
    }

    private boolean hasVector(String value) { return value != null && !value.isBlank(); }

    private boolean validEvidence(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            JsonNode node = objectMapper.readTree(value);
            return node.isArray() && !node.isEmpty();
        } catch (Exception e) { return false; }
    }

    private String normalize(String note) {
        if (note == null) return null;
        String value = note.trim();
        if (value.length() > 500) throw new ReviewException(400, "处理说明不能超过 500 个字符");
        return value;
    }

    public record ResolutionRequest(String resolution, String note) {}
    public record ConflictResolution(Long jobId, Long conflictId, String status,
                                     String resolution, Long reviewerId, Date reviewedAt) {}
    public record GateReport(boolean passed, int unreviewedUnits, int missingEvidenceUnits,
                             int blockingConflicts, int warningConflicts, List<String> blockers) {}
    public static class ReviewException extends RuntimeException {
        private final int status;
        public ReviewException(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }
}
