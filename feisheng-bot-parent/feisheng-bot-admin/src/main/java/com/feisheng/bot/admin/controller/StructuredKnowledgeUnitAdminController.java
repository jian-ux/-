package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.service.StructuredKnowledgeExtractionService;
import com.feisheng.bot.admin.service.StructuredKnowledgeUnitReviewService;
import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeSemanticUnitMapper;
import com.feisheng.bot.knowledge.service.StructuredKnowledgeUnitIndexService;
import org.springframework.util.StringUtils;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/admin/knowledge/semantic-unit")
public class StructuredKnowledgeUnitAdminController {
    private final StructuredKnowledgeExtractionService extractionService;
    private final StructuredKnowledgeUnitReviewService reviewService;
    private final BotKnowledgeSemanticUnitMapper unitMapper;
    private final StructuredKnowledgeUnitIndexService indexService;

    public StructuredKnowledgeUnitAdminController(
            StructuredKnowledgeExtractionService extractionService,
            StructuredKnowledgeUnitReviewService reviewService,
            BotKnowledgeSemanticUnitMapper unitMapper,
            StructuredKnowledgeUnitIndexService indexService) {
        this.extractionService = extractionService;
        this.reviewService = reviewService;
        this.unitMapper = unitMapper;
        this.indexService = indexService;
    }

    @PostMapping("/extract/{documentId}")
    public R<StructuredKnowledgeExtractionService.ExtractionReport> extract(
            @PathVariable Long documentId,
            @RequestBody(required = false) ExtractionRequest request) {
        try {
            Long preferredModelId = request == null ? null : request.preferredModelId();
            return R.ok(extractionService.extractDocument(documentId, preferredModelId));
        } catch (StructuredKnowledgeExtractionService.ExtractionException e) {
            return R.fail(e.status(), e.getMessage());
        }
    }

    @GetMapping("/list")
    public R<Page<UnitView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long documentId,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<BotKnowledgeSemanticUnit> query =
            new LambdaQueryWrapper<BotKnowledgeSemanticUnit>()
                .eq(documentId != null, BotKnowledgeSemanticUnit::getDocumentId, documentId)
                .eq(StringUtils.hasText(status), BotKnowledgeSemanticUnit::getStatus,
                    StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null)
                .orderByDesc(BotKnowledgeSemanticUnit::getId);
        Page<BotKnowledgeSemanticUnit> result = unitMapper.selectPage(
            new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)), query);
        Page<UnitView> response = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        response.setRecords(result.getRecords().stream().map(UnitView::from).toList());
        return R.ok(response);
    }

    @GetMapping("/index/status")
    public R<StructuredKnowledgeUnitIndexService.IndexStatus> indexStatus() {
        return R.ok(indexService.status());
    }

    @PostMapping("/{unitId}/approve")
    public R<StructuredKnowledgeUnitReviewService.ReviewResult> approve(
            @PathVariable Long unitId,
            @RequestBody(required = false) ReviewRequest request,
            Authentication authentication) {
        try {
            return R.ok(reviewService.approve(
                unitId, operatorId(authentication), request == null ? null : request.reason()));
        } catch (StructuredKnowledgeUnitReviewService.ReviewException e) {
            return R.fail(e.status(), e.getMessage());
        }
    }

    @PostMapping("/{unitId}/reject")
    public R<StructuredKnowledgeUnitReviewService.ReviewResult> reject(
            @PathVariable Long unitId,
            @RequestBody(required = false) ReviewRequest request,
            Authentication authentication) {
        try {
            if (request == null || !StringUtils.hasText(request.reason())) {
                return R.fail(400, "拒绝结构化知识时必须填写原因");
            }
            return R.ok(reviewService.reject(
                unitId, operatorId(authentication), request.reason()));
        } catch (StructuredKnowledgeUnitReviewService.ReviewException e) {
            return R.fail(e.status(), e.getMessage());
        }
    }

    @PostMapping("/batch/approve")
    public R<StructuredKnowledgeUnitReviewService.BatchReviewResult> approveBatch(
            @RequestBody BatchReviewRequest request,
            Authentication authentication) {
        try {
            if (request == null) return R.fail(400, "请选择至少一条结构化知识");
            return R.ok(reviewService.approveBatch(
                request.unitIds(), operatorId(authentication), request.reason()));
        } catch (StructuredKnowledgeUnitReviewService.ReviewException e) {
            return R.fail(e.status(), e.getMessage());
        }
    }

    @PostMapping("/batch/reject")
    public R<StructuredKnowledgeUnitReviewService.BatchReviewResult> rejectBatch(
            @RequestBody BatchReviewRequest request,
            Authentication authentication) {
        try {
            if (request == null || !StringUtils.hasText(request.reason())) {
                return R.fail(400, "批量拒绝结构化知识时必须填写原因");
            }
            return R.ok(reviewService.rejectBatch(
                request.unitIds(), operatorId(authentication), request.reason()));
        } catch (StructuredKnowledgeUnitReviewService.ReviewException e) {
            return R.fail(e.status(), e.getMessage());
        }
    }

    public record ExtractionRequest(Long preferredModelId) {}

    public record ReviewRequest(String reason) {}

    public record BatchReviewRequest(List<Long> unitIds, String reason) {}

    private Long operatorId(Authentication authentication) {
        return authentication == null ? null : (Long) authentication.getPrincipal();
    }

    public record UnitView(
            Long id,
            Long documentId,
            Long categoryId,
            String unitKey,
            String unitType,
            String question,
            String statement,
            String intent,
            String entitiesJson,
            String conditionsJson,
            String exclusionsJson,
            String queryVariantsJson,
            String evidenceChunkIdsJson,
            String sourceSpansJson,
            String metadataJson,
            Double extractionConfidence,
            String extractorModel,
            String promptVersion,
            String schemaVersion,
            String sourceHash,
            String status,
            Long reviewedBy,
            Date reviewedAt,
            String reviewReason,
            boolean embeddingReady,
            Date createTime,
            Date updateTime) {
        private static UnitView from(BotKnowledgeSemanticUnit unit) {
            return new UnitView(
                unit.getId(), unit.getDocumentId(), unit.getCategoryId(), unit.getUnitKey(),
                unit.getUnitType(), unit.getQuestion(), unit.getStatement(), unit.getIntent(),
                unit.getEntitiesJson(), unit.getConditionsJson(), unit.getExclusionsJson(),
                unit.getQueryVariantsJson(), unit.getEvidenceChunkIdsJson(),
                unit.getSourceSpansJson(), unit.getMetadataJson(),
                unit.getExtractionConfidence(), unit.getExtractorModel(),
                unit.getPromptVersion(), unit.getSchemaVersion(), unit.getSourceHash(),
                unit.getStatus(), unit.getReviewedBy(), unit.getReviewedAt(),
                unit.getReviewReason(), StringUtils.hasText(unit.getEmbedding()),
                unit.getCreateTime(), unit.getUpdateTime());
        }
    }
}
