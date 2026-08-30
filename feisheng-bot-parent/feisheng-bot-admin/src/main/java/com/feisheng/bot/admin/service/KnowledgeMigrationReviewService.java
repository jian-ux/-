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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Human conflict decisions and the document-level release gate. */
@Service
public class KnowledgeMigrationReviewService {
    private static final String PENDING = "PENDING";
    private static final int COMPLETED_DOCUMENT_STATUS = 2;
    private static final String DRAFT = "DRAFT";
    private static final String KNOWLEDGE = "KNOWLEDGE";
    private final ValidationPolicy validationPolicy;
    private final BotKnowledgeMigrationJobMapper jobMapper;
    private final BotKnowledgeConflictMapper conflictMapper;
    private final BotKnowledgeSemanticUnitMapper unitMapper;
    private final BotKnowledgeDocumentMapper documentMapper;
    private final BotKnowledgeChunkMapper chunkMapper;
    private final ObjectMapper objectMapper;

    @Autowired
    public KnowledgeMigrationReviewService(BotKnowledgeMigrationJobMapper jobMapper,
                                           BotKnowledgeConflictMapper conflictMapper,
                                           BotKnowledgeSemanticUnitMapper unitMapper,
                                           BotKnowledgeDocumentMapper documentMapper,
                                           BotKnowledgeChunkMapper chunkMapper,
                                           ObjectMapper objectMapper) {
        this(jobMapper, conflictMapper, unitMapper, documentMapper, chunkMapper, objectMapper,
            ValidationPolicy.defaults());
    }

    public KnowledgeMigrationReviewService(BotKnowledgeMigrationJobMapper jobMapper,
                                           BotKnowledgeConflictMapper conflictMapper,
                                           BotKnowledgeSemanticUnitMapper unitMapper,
                                           BotKnowledgeDocumentMapper documentMapper,
                                           BotKnowledgeChunkMapper chunkMapper,
                                           ObjectMapper objectMapper,
                                           ValidationPolicy validationPolicy) {
        this.jobMapper = jobMapper;
        this.conflictMapper = conflictMapper;
        this.unitMapper = unitMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.objectMapper = objectMapper;
        this.validationPolicy = validationPolicy == null ? ValidationPolicy.defaults() : validationPolicy;
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
        String note = normalize(request.note());
        if (note == null) throw new ReviewException(400, "冲突处理必须填写原因");
        if (nonOverridable(conflict)) {
            throw new ReviewException(409, "确定性质量阻断必须先修复目标单元并重新检测冲突");
        }
        if (!allowedResolution(conflict.getSeverity(), resolution)) {
            throw new ReviewException(409, "当前冲突级别不允许该处理方式");
        }
        if (!PENDING.equals(conflict.getStatus())) {
            throw new ReviewException(409, "冲突已处理，请刷新后重试");
        }
        Date now = new Date();
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
            return reportFromPersisted(job);
        }
        throw new ReviewException(400, "文档确认必须填写原因");
    }

    public GateReport confirmDocument(Long jobId, ConfirmationRequest request, Long reviewerId) {
        BotKnowledgeMigrationJob job = requireJob(jobId);
        requireReviewer(reviewerId);
        String reason = request == null ? null : normalize(request.reason());
        if (reason == null) throw new ReviewException(400, "文档确认必须填写原因");
        if (KnowledgeMigrationStatus.READY_TO_SWITCH.name().equals(job.getStatus())) {
            return reportFromPersisted(job);
        }
        if (!KnowledgeMigrationStatus.REVIEW_REQUIRED.name().equals(job.getStatus())) {
            throw new ReviewException(409, "当前任务尚未进入人工审核阶段");
        }
        List<String> blockers = new ArrayList<>();
        BotKnowledgeDocument target = job.getTargetDocumentId() == null
            ? null : documentMapper.selectById(job.getTargetDocumentId());
        if (!validTarget(job, target)) blockers.add("目标文档不存在或不是当前知识集的已完成草稿");
        Map<Long, BotKnowledgeChunk> targetChunks = targetChunks(job.getTargetDocumentId());
        List<BotKnowledgeSemanticUnit> units = unitMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeSemanticUnit>()
                .eq(BotKnowledgeSemanticUnit::getDocumentId, job.getTargetDocumentId())
                .ne(BotKnowledgeSemanticUnit::getDeleted, 1));
        int unreviewed = 0;
        int missingEvidence = 0;
        for (BotKnowledgeSemanticUnit unit : units == null ? List.<BotKnowledgeSemanticUnit>of() : units) {
            if (!"APPROVED".equals(unit.getStatus()) && !"REJECTED".equals(unit.getStatus())) unreviewed++;
            if ("APPROVED".equals(unit.getStatus()) && !validUnitQuality(unit, targetChunks)) {
                missingEvidence++;
            }
        }
        if (unreviewed > 0) blockers.add("存在未审核结构化单元: " + unreviewed);
        if (missingEvidence > 0) blockers.add("存在缺少向量、证据或质量不合格的已通过单元: " + missingEvidence);
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
        if (!blockers.isEmpty()) {
            return new GateReport(false, unreviewed, missingEvidence, blocking, warning, List.copyOf(blockers));
        }
        Date reviewedAt = new Date();
        GateReport report = passedReport();
        String audit = confirmationAudit(job, report, reason, reviewerId, reviewedAt);
        int changed = jobMapper.confirm(job.getId(), value(job.getLockVersion()), reviewerId,
            reviewedAt, reason, audit);
        if (changed != 1) {
            BotKnowledgeMigrationJob persisted = jobMapper.selectById(jobId);
            if (persisted != null && KnowledgeMigrationStatus.READY_TO_SWITCH.name().equals(persisted.getStatus())) {
                return reportFromPersisted(persisted);
            }
            throw new ReviewException(409, "任务已被其他审核人更新，请刷新后重试");
        }
        job.setStatus(KnowledgeMigrationStatus.READY_TO_SWITCH.name());
        job.setCurrentStep(KnowledgeMigrationStatus.READY_TO_SWITCH.name());
        job.setReviewerId(reviewerId);
        job.setReviewedAt(reviewedAt);
        job.setReviewReason(reason);
        job.setReviewAuditJson(audit);
        job.setLockVersion(value(job.getLockVersion()) + 1);
        return report;
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

    private boolean validTarget(BotKnowledgeMigrationJob job, BotKnowledgeDocument target) {
        return target != null && Objects.equals(target.getId(), job.getTargetDocumentId())
            && COMPLETED_DOCUMENT_STATUS == Objects.requireNonNullElse(target.getStatus(), -1)
            && DRAFT.equalsIgnoreCase(target.getPublishStatus())
            && KNOWLEDGE.equalsIgnoreCase(Objects.toString(target.getSourceScope(), ""))
            && !Integer.valueOf(1).equals(target.getDeleted())
            && job.getKnowledgeSetKey() != null && !job.getKnowledgeSetKey().isBlank()
            && Objects.equals(job.getKnowledgeSetKey(), target.getKnowledgeSetKey())
            && job.getTargetVersionId() != null && target.getDocumentVersion() != null
            && Objects.equals(job.getTargetVersionId(), target.getDocumentVersion().longValue());
    }

    private Map<Long, BotKnowledgeChunk> targetChunks(Long documentId) {
        if (documentId == null) return Map.of();
        List<BotKnowledgeChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<BotKnowledgeChunk>()
            .eq(BotKnowledgeChunk::getDocumentId, documentId));
        Map<Long, BotKnowledgeChunk> result = new HashMap<>();
        if (chunks != null) for (BotKnowledgeChunk chunk : chunks) {
            if (chunk != null && chunk.getId() != null) result.put(chunk.getId(), chunk);
        }
        return result;
    }

    private boolean validUnitQuality(BotKnowledgeSemanticUnit unit,
                                     Map<Long, BotKnowledgeChunk> targetChunks) {
        if (!validVector(unit.getEmbedding(), unit.getEmbeddingDimensions())) return false;
        Double confidence = unit.getExtractionConfidence();
        if (confidence == null || !Double.isFinite(confidence)
                || confidence < validationPolicy.minExtractionConfidence() || confidence > 1.0d) return false;
        if (!validEvidence(unit.getEvidenceChunkIdsJson(), targetChunks)) return false;
        return validSourceSpans(unit.getSourceSpansJson(), unit.getEvidenceChunkIdsJson(), targetChunks);
    }

    private boolean validVector(String value, Integer expectedDimensions) {
        if (value == null || value.isBlank()) return false;
        try {
            JsonNode node = objectMapper.readTree(value);
            if (!node.isArray() || node.isEmpty()) return false;
            if (expectedDimensions != null && expectedDimensions > 0 && node.size() != expectedDimensions) return false;
            for (JsonNode element : node) if (!element.isNumber() || !Double.isFinite(element.doubleValue())) return false;
            return true;
        } catch (Exception e) { return false; }
    }

    private boolean validEvidence(String value, Map<Long, BotKnowledgeChunk> chunks) {
        if (value == null || value.isBlank()) return false;
        try {
            JsonNode node = objectMapper.readTree(value);
            if (!node.isArray() || node.isEmpty()) return false;
            Set<Long> ids = new HashSet<>();
            for (JsonNode element : node) {
                if (!element.isIntegralNumber() || !element.canConvertToLong() || element.longValue() <= 0) return false;
                if (!ids.add(element.longValue())) return false;
                if (!chunks.containsKey(element.longValue())) return false;
            }
            return true;
        } catch (Exception e) { return false; }
    }

    private boolean validSourceSpans(String value, String evidenceJson,
                                     Map<Long, BotKnowledgeChunk> chunks) {
        if (value == null || value.isBlank()) return false;
        try {
            JsonNode spans = objectMapper.readTree(value);
            JsonNode evidence = objectMapper.readTree(evidenceJson);
            if (!spans.isArray() || spans.isEmpty() || !evidence.isArray()) return false;
            Set<Long> evidenceIds = new HashSet<>();
            for (JsonNode id : evidence) evidenceIds.add(id.longValue());
            Set<Long> spanIds = new HashSet<>();
            for (JsonNode span : spans) {
                if (!span.isObject()) return false;
                JsonNode chunkId = span.get("chunkId");
                JsonNode start = span.get("start");
                JsonNode end = span.get("end");
                JsonNode quote = span.get("quote");
                if (chunkId == null || !chunkId.isIntegralNumber() || !evidenceIds.contains(chunkId.longValue())
                        || !validOffset(start) || !validOffset(end)
                        || start.intValue() < 0 || end.intValue() <= start.intValue()
                        || quote == null || !quote.isTextual() || quote.textValue().isBlank()) return false;
                spanIds.add(chunkId.longValue());
                BotKnowledgeChunk chunk = chunks.get(chunkId.longValue());
                if (chunk != null && (chunk.getContent() == null || end.intValue() > chunk.getContent().length()
                        || !chunk.getContent().substring(start.intValue(), end.intValue()).equals(quote.textValue()))) return false;
            }
            return spanIds.equals(evidenceIds);
        } catch (Exception e) { return false; }
    }

    private boolean validOffset(JsonNode value) {
        if (value == null || !value.isNumber()) return false;
        double number = value.doubleValue();
        return Double.isFinite(number) && number >= Integer.MIN_VALUE
            && number <= Integer.MAX_VALUE && Math.rint(number) == number;
    }

    private String confirmationAudit(BotKnowledgeMigrationJob job, GateReport report,
                                     String reason, Long reviewerId, Date reviewedAt) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode before = root.putObject("before");
        before.put("status", Objects.toString(job.getStatus(), ""));
        before.put("lockVersion", value(job.getLockVersion()));
        before.put("targetDocumentId", job.getTargetDocumentId() == null ? 0L : job.getTargetDocumentId());
        ObjectNode after = root.putObject("after");
        after.put("status", KnowledgeMigrationStatus.READY_TO_SWITCH.name());
        after.put("reviewerId", reviewerId == null ? 0L : reviewerId);
        after.put("reviewedAt", reviewedAt.getTime());
        root.put("reason", reason);
        try { root.set("gateReport", objectMapper.valueToTree(report)); return objectMapper.writeValueAsString(root); }
        catch (Exception e) { throw new ReviewException(500, "无法记录文档确认审计信息"); }
    }

    private GateReport reportFromPersisted(BotKnowledgeMigrationJob job) {
        if (job.getReviewAuditJson() != null) {
            try {
                JsonNode report = objectMapper.readTree(job.getReviewAuditJson()).path("gateReport");
                if (report.isObject()) return objectMapper.treeToValue(report, GateReport.class);
            } catch (Exception ignored) { }
        }
        return passedReport();
    }

    private boolean nonOverridable(BotKnowledgeConflict conflict) {
        if (Objects.equals(conflict.getCandidateUnitId(), 0L)) return true;
        String type = Objects.toString(conflict.getConflictType(), "");
        if (Set.of("VECTOR", "EVIDENCE", "EVIDENCE_OR_SCOPE", "QUALITY").contains(type)) return true;
        try {
            JsonNode rule = objectMapper.readTree(conflict.getRuleResult());
            return "UNKNOWN".equalsIgnoreCase(rule.path("judgment").asText())
                || "UNKNOWN".equalsIgnoreCase(rule.path("relation").asText());
        }
        catch (Exception ignored) { return true; }
    }

    private static long value(Long value) { return value == null ? 0L : value; }

    private String normalize(String note) {
        if (note == null) return null;
        String value = note.trim();
        if (value.isEmpty()) return null;
        if (value.length() > 500) throw new ReviewException(400, "处理说明不能超过 500 个字符");
        return value;
    }

    public record ResolutionRequest(String resolution, String note) {}
    public record ConfirmationRequest(String reason) {}
    public record ValidationPolicy(double minExtractionConfidence) {
        public ValidationPolicy {
            if (!Double.isFinite(minExtractionConfidence) || minExtractionConfidence < 0.0d
                    || minExtractionConfidence > 1.0d) {
                throw new IllegalArgumentException("minExtractionConfidence must be in [0, 1]");
            }
        }
        public static ValidationPolicy defaults() { return new ValidationPolicy(0.5d); }
    }
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
