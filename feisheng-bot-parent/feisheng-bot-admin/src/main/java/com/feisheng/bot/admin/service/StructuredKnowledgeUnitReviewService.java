package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.common.dto.StructuredKnowledgeUnit;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import com.feisheng.bot.knowledge.entity.BotKnowledgeDocument;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeSemanticUnitMapper;
import com.feisheng.bot.knowledge.service.StructuredKnowledgeUnitIndexService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

/** Human-review state transitions for extracted semantic units. */
@Service
public class StructuredKnowledgeUnitReviewService {
    private final BotKnowledgeSemanticUnitMapper unitMapper;
    private final BotKnowledgeDocumentMapper documentMapper;
    private final BotKnowledgeChunkMapper chunkMapper;
    private final StructuredKnowledgeUnitIndexService indexService;
    private final ObjectMapper objectMapper;

    public StructuredKnowledgeUnitReviewService(
            BotKnowledgeSemanticUnitMapper unitMapper,
            BotKnowledgeDocumentMapper documentMapper,
            BotKnowledgeChunkMapper chunkMapper,
            StructuredKnowledgeUnitIndexService indexService,
            ObjectMapper objectMapper) {
        this.unitMapper = unitMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.indexService = indexService;
        this.objectMapper = objectMapper;
    }

    public ReviewResult approve(Long unitId) {
        return approve(unitId, null, null);
    }

    public ReviewResult approve(Long unitId, Long reviewerId, String reason) {
        ReviewResult result = approveWithoutSync(unitId, reviewerId, reason);
        return result.changed() ? syncedResult(unitId, "APPROVED") : result;
    }

    private ReviewResult approveWithoutSync(Long unitId, Long reviewerId, String reason) {
        BotKnowledgeSemanticUnit unit = requireUnit(unitId);
        validateOwningDocument(unit);
        if ("APPROVED".equals(unit.getStatus())) {
            return new ReviewResult(unitId, "APPROVED", false, true, null);
        }
        if (!"DRAFT".equals(unit.getStatus())) {
            throw new ReviewException(409, "只有待审核的结构化知识可以通过审核");
        }
        if (!StringUtils.hasText(unit.getEmbedding())) {
            throw new ReviewException(409, "结构化知识尚未生成向量，不能通过审核");
        }
        validateEvidence(unit);
        transition(unit, "APPROVED", reviewerId, reason);
        return new ReviewResult(unitId, "APPROVED", true, true, null);
    }

    public ReviewResult reject(Long unitId) {
        return reject(unitId, null, null);
    }

    public ReviewResult reject(Long unitId, Long reviewerId, String reason) {
        ReviewResult result = rejectWithoutSync(unitId, reviewerId, reason);
        return result.changed() ? syncedResult(unitId, "REJECTED") : result;
    }

    private ReviewResult rejectWithoutSync(Long unitId, Long reviewerId, String reason) {
        BotKnowledgeSemanticUnit unit = requireUnit(unitId);
        if ("REJECTED".equals(unit.getStatus())) {
            return new ReviewResult(unitId, "REJECTED", false, true, null);
        }
        transition(unit, "REJECTED", reviewerId, reason);
        return new ReviewResult(unitId, "REJECTED", true, true, null);
    }

    public BatchReviewResult approveBatch(List<Long> unitIds, Long reviewerId, String reason) {
        return reviewBatch(unitIds, reviewerId, reason, "APPROVE");
    }

    public BatchReviewResult rejectBatch(List<Long> unitIds, Long reviewerId, String reason) {
        return reviewBatch(unitIds, reviewerId, reason, "REJECT");
    }

    private BatchReviewResult reviewBatch(List<Long> unitIds, Long reviewerId,
                                          String reason, String action) {
        List<Long> distinctIds = validateBatchIds(unitIds);
        String normalizedReason = normalizeReason(reason);
        List<BatchItemResult> items = new ArrayList<>(distinctIds.size());
        int succeeded = 0;
        int changed = 0;
        for (Long unitId : distinctIds) {
            try {
                ReviewResult result = "APPROVE".equals(action)
                    ? approveWithoutSync(unitId, reviewerId, normalizedReason)
                    : rejectWithoutSync(unitId, reviewerId, normalizedReason);
                succeeded++;
                if (result.changed()) changed++;
                items.add(new BatchItemResult(unitId, true, result.status(),
                    result.changed(), null, null));
            } catch (ReviewException e) {
                items.add(new BatchItemResult(unitId, false, null, false,
                    e.status(), e.getMessage()));
            }
        }

        boolean indexSyncSuccess = true;
        String indexSyncError = null;
        if (changed > 0) {
            StructuredKnowledgeUnitIndexService.SyncReport sync = indexService.sync();
            indexSyncSuccess = sync.success();
            indexSyncError = sync.error();
        }
        return new BatchReviewResult(action, distinctIds.size(), succeeded,
            distinctIds.size() - succeeded, changed, indexSyncSuccess,
            indexSyncError, List.copyOf(items));
    }

    private List<Long> validateBatchIds(List<Long> unitIds) {
        if (unitIds == null || unitIds.isEmpty()) {
            throw new ReviewException(400, "请选择至少一条结构化知识");
        }
        if (unitIds.size() > 200) {
            throw new ReviewException(400, "单次批量审核不能超过 200 条");
        }
        LinkedHashSet<Long> distinctIds = new LinkedHashSet<>();
        for (Long unitId : unitIds) {
            if (unitId == null) {
                throw new ReviewException(400, "结构化知识 ID 不能为空");
            }
            distinctIds.add(unitId);
        }
        return List.copyOf(distinctIds);
    }

    private void transition(BotKnowledgeSemanticUnit unit, String targetStatus,
                            Long reviewerId, String reason) {
        Date reviewedAt = new Date();
        String normalizedReason = normalizeReason(reason);
        int changed = unitMapper.transitionReview(unit.getId(), unit.getStatus(), targetStatus,
            reviewerId, reviewedAt, normalizedReason);
        if (changed != 1) {
            throw new ReviewException(409, "审核状态已被其他操作修改，请刷新后重试");
        }
        unit.setStatus(targetStatus);
        unit.setReviewedBy(reviewerId);
        unit.setReviewedAt(reviewedAt);
        unit.setReviewReason(normalizedReason);
    }

    private ReviewResult syncedResult(Long unitId, String status) {
        StructuredKnowledgeUnitIndexService.SyncReport sync = indexService.sync();
        return new ReviewResult(unitId, status, true, sync.success(), sync.error());
    }

    private BotKnowledgeSemanticUnit requireUnit(Long unitId) {
        if (unitId == null) throw new ReviewException(400, "结构化知识 ID 不能为空");
        BotKnowledgeSemanticUnit unit = unitMapper.selectById(unitId);
        if (unit == null) throw new ReviewException(404, "结构化知识不存在");
        return unit;
    }

    private void validateOwningDocument(BotKnowledgeSemanticUnit unit) {
        BotKnowledgeDocument document = documentMapper.selectById(unit.getDocumentId());
        if (document == null) {
            throw new ReviewException(409, "所属文档不存在或已删除，请重新抽取");
        }
        if (!Integer.valueOf(2).equals(document.getStatus())) {
            throw new ReviewException(409, "所属文档完成入库后才能通过审核");
        }
        if (!"KNOWLEDGE".equalsIgnoreCase(document.getSourceScope())) {
            throw new ReviewException(409, "只有知识库文档可以发布结构化知识");
        }
    }

    private String normalizeReason(String reason) {
        if (!StringUtils.hasText(reason)) return null;
        String normalized = reason.trim();
        if (normalized.length() > 500) {
            throw new ReviewException(400, "审核备注不能超过 500 个字符");
        }
        return normalized;
    }

    private void validateEvidence(BotKnowledgeSemanticUnit unit) {
        List<Long> evidenceIds;
        List<StructuredKnowledgeUnit.SourceSpan> spans;
        try {
            evidenceIds = objectMapper.readValue(unit.getEvidenceChunkIdsJson(),
                new TypeReference<List<Long>>() {});
            spans = objectMapper.readValue(unit.getSourceSpansJson(),
                new TypeReference<List<StructuredKnowledgeUnit.SourceSpan>>() {});
        } catch (Exception e) {
            throw new ReviewException(409, "结构化知识的证据 JSON 无效");
        }
        if (evidenceIds == null || evidenceIds.isEmpty() || spans == null || spans.isEmpty()) {
            throw new ReviewException(409, "结构化知识缺少原文证据");
        }

        Set<Long> distinctIds = new LinkedHashSet<>(evidenceIds);
        List<BotKnowledgeChunk> chunks = chunkMapper.selectBatchIds(distinctIds);
        Map<Long, BotKnowledgeChunk> chunksById = new HashMap<>();
        if (chunks != null) {
            for (BotKnowledgeChunk chunk : chunks) chunksById.put(chunk.getId(), chunk);
        }
        if (chunksById.size() != distinctIds.size()) {
            throw new ReviewException(409, "部分证据分片已不存在，请重新抽取");
        }
        for (Long evidenceId : distinctIds) {
            BotKnowledgeChunk chunk = chunksById.get(evidenceId);
            if (!unit.getDocumentId().equals(chunk.getDocumentId())) {
                throw new ReviewException(409, "证据分片不属于当前文档");
            }
            if (chunk.getDeleted() != null && chunk.getDeleted() != 0) {
                throw new ReviewException(409, "证据分片已删除，请重新抽取");
            }
            if (!"APPROVED".equals(chunk.getStatus())) {
                throw new ReviewException(409, "所有证据分片审核通过后才能发布结构化知识");
            }
        }
        for (StructuredKnowledgeUnit.SourceSpan span : spans) {
            BotKnowledgeChunk chunk = chunksById.get(span.chunkId());
            if (chunk == null || chunk.getContent() == null
                    || span.start() < 0 || span.end() > chunk.getContent().length()
                    || span.end() <= span.start()
                    || !span.quote().equals(chunk.getContent().substring(
                        span.start(), span.end()))) {
                throw new ReviewException(409, "原文证据已变化，请重新抽取");
            }
        }
    }

    public record ReviewResult(Long unitId, String status, boolean changed,
                               boolean indexSyncSuccess, String indexSyncError) {}

    public record BatchItemResult(Long unitId, boolean success, String status,
                                  boolean changed, Integer errorCode, String error) {}

    public record BatchReviewResult(String action, int requested, int succeeded,
                                    int failed, int changed, boolean indexSyncSuccess,
                                    String indexSyncError, List<BatchItemResult> items) {}

    public static class ReviewException extends RuntimeException {
        private final int status;

        public ReviewException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
