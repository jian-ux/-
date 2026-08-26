package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotUnmatchedQuestion;
import com.feisheng.bot.admin.mapper.BotUnmatchedQuestionMapper;
import com.feisheng.bot.admin.service.BadCaseImprovementAdvisor;
import com.feisheng.bot.admin.service.BadCaseQualityService;
import com.feisheng.bot.admin.service.QuestionClusteringService;
import com.feisheng.bot.common.vo.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/unmatched")
public class UnmatchedQuestionController {
    private final BotUnmatchedQuestionMapper mapper;
    private final QuestionClusteringService clusteringService;
    private final BadCaseQualityService qualityService;

    @Autowired
    public UnmatchedQuestionController(BotUnmatchedQuestionMapper m,
                                       QuestionClusteringService service,
                                       BadCaseQualityService qualityService) {
        mapper = m;
        clusteringService = service;
        this.qualityService = qualityService;
    }

    /** Compatibility constructor for callers that only use the original list/resolve APIs. */
    public UnmatchedQuestionController(BotUnmatchedQuestionMapper m) {
        mapper = m;
        clusteringService = null;
        qualityService = null;
    }

    @GetMapping("/quality")
    public R<BadCaseQualityService.QualitySummary> quality() {
        return R.ok(qualityService.summarize());
    }

    /** Compatibility overload for callers that do not need a review filter. */
    public R<Page<BotUnmatchedQuestion>> list(int page, int size) {
        return list(page, size, null);
    }

    @GetMapping("/list")
    public R<Page<BotUnmatchedQuestion>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String reviewStatus) {
        LambdaQueryWrapper<BotUnmatchedQuestion> query = new LambdaQueryWrapper<>();
        if ("PENDING".equalsIgnoreCase(reviewStatus)
                || "REVIEWED".equalsIgnoreCase(reviewStatus)) {
            query.eq(BotUnmatchedQuestion::getReviewStatus,
                reviewStatus.trim().toUpperCase(Locale.ROOT));
        }
        Page<BotUnmatchedQuestion> result = mapper.selectPage(new Page<>(page, size),
            query.orderByDesc(BotUnmatchedQuestion::getSimilarCount)
                .orderByDesc(BotUnmatchedQuestion::getCreateTime));
        result.getRecords().forEach(question -> question.setImprovementAdvice(
            BadCaseImprovementAdvisor.advise(question.getTriggerTypes())));
        return R.ok(result);
    }

    @PutMapping("/{id}/resolve")
    public R<Void> resolve(@PathVariable Long id) {
        BotUnmatchedQuestion q = mapper.selectById(id);
        if (q != null) { q.setIsResolved(1); mapper.updateById(q); }
        return R.ok();
    }

    @PutMapping("/{id}/review")
    public R<BotUnmatchedQuestion> review(@PathVariable Long id,
                                          @RequestBody ReviewRequest request,
                                          Authentication authentication) {
        if (request == null || request.correct() == null
                || !StringUtils.hasText(request.decision())) {
            return R.fail(400, "请填写复核结果和正确决策");
        }
        String decision = request.decision().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ANSWER", "CLARIFY", "NO_ANSWER", "HANDOFF").contains(decision)) {
            return R.fail(400, "正确决策只能是回答、追问、无答案或转人工");
        }
        if (request.category() != null && request.category().trim().length() > 40) {
            return R.fail(400, "错误类型不能超过40个字符");
        }
        if (request.note() != null && request.note().trim().length() > 1000) {
            return R.fail(400, "复核备注不能超过1000个字符");
        }
        BotUnmatchedQuestion question = mapper.selectById(id);
        if (question == null) return R.fail(404, "问题不存在");
        question.setReviewStatus("REVIEWED");
        question.setReviewDecision(decision);
        question.setReviewCorrect(request.correct() ? 1 : 0);
        question.setReviewCategory(trimToNull(request.category()));
        question.setReviewNote(trimToNull(request.note()));
        question.setReviewedBy(operatorId(authentication));
        question.setReviewedAt(new java.util.Date());
        mapper.updateById(question);
        return R.ok(question);
    }

    @PostMapping("/cluster")
    public R<QuestionClusteringService.ClusterResult> cluster(
            @RequestParam(defaultValue = "false") boolean includeResolved,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(defaultValue = "0.82") double threshold,
            @RequestParam(defaultValue = "2") int minClusterSize) {
        return R.ok(clusteringService.cluster(includeResolved, limit, threshold, minClusterSize));
    }

    @PostMapping("/cluster/run")
    public R<QuestionClusteringService.ClusterReviewResult> runCluster(
            @RequestParam(defaultValue = "false") boolean includeResolved,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(defaultValue = "0.82") double threshold,
            @RequestParam(defaultValue = "2") int minClusterSize) {
        return R.ok(clusteringService.runAndSave(includeResolved, limit, threshold, minClusterSize));
    }

    @GetMapping("/cluster/list")
    public R<QuestionClusteringService.ClusterReviewResult> latestCluster() {
        return R.ok(clusteringService.latestReview());
    }

    @PutMapping("/cluster/{id}/title")
    public R<Void> renameCluster(@PathVariable Long id, @RequestBody TitleRequest request) {
        if (request == null || request.title() == null || request.title().isBlank()) {
            return R.fail(400, "聚类标题不能为空");
        }
        if (request.title().trim().length() > 500) {
            return R.fail(400, "聚类标题不能超过500个字符");
        }
        return clusteringService.rename(id, request.title())
            ? R.ok() : R.fail(404, "聚类不存在");
    }

    @PutMapping("/cluster/{id}/ignore")
    public R<Void> ignoreCluster(@PathVariable Long id) {
        return clusteringService.ignore(id)
            ? R.ok() : R.fail(404, "聚类不存在");
    }

    @DeleteMapping("/cluster/{id}")
    public R<Void> deleteCluster(@PathVariable Long id) {
        QuestionClusteringService.MutationResult result = clusteringService.delete(id);
        return result.success() ? R.ok() : R.fail(result.code(), result.message());
    }

    @PostMapping("/cluster/merge")
    public R<Void> mergeClusters(@RequestBody MergeRequest request) {
        if (request == null || request.targetId() == null || request.sourceIds() == null) {
            return R.fail(400, "请选择要合并的聚类");
        }
        QuestionClusteringService.MutationResult result = clusteringService.merge(
            request.targetId(), request.sourceIds());
        return result.success() ? R.ok() : R.fail(result.code(), result.message());
    }

    @PostMapping("/cluster/{id}/split")
    public R<Void> splitCluster(@PathVariable Long id, @RequestBody SplitRequest request) {
        if (request == null || request.questionIds() == null || request.questionIds().isEmpty()) {
            return R.fail(400, "请选择要拆分的问题");
        }
        if (request.title() != null && request.title().trim().length() > 500) {
            return R.fail(400, "聚类标题不能超过500个字符");
        }
        QuestionClusteringService.MutationResult result = clusteringService.split(
            id, request.questionIds(), request.title());
        return result.success() ? R.ok() : R.fail(result.code(), result.message());
    }

    public record TitleRequest(String title) {}
    public record MergeRequest(Long targetId, java.util.List<Long> sourceIds) {}
    public record SplitRequest(java.util.List<Long> questionIds, String title) {}
    public record ReviewRequest(String decision, Boolean correct, String category, String note) {}

    private Long operatorId(Authentication authentication) {
        return authentication == null ? null : (Long) authentication.getPrincipal();
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) return null;
        return value.trim();
    }
}
