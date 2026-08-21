package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotUnmatchedQuestion;
import com.feisheng.bot.admin.mapper.BotUnmatchedQuestionMapper;
import com.feisheng.bot.admin.service.QuestionClusteringService;
import com.feisheng.bot.common.vo.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/unmatched")
public class UnmatchedQuestionController {
    private final BotUnmatchedQuestionMapper mapper;
    private final QuestionClusteringService clusteringService;

    @Autowired
    public UnmatchedQuestionController(BotUnmatchedQuestionMapper m,
                                       QuestionClusteringService service) {
        mapper = m;
        clusteringService = service;
    }

    /** Compatibility constructor for callers that only use the original list/resolve APIs. */
    public UnmatchedQuestionController(BotUnmatchedQuestionMapper m) {
        mapper = m;
        clusteringService = null;
    }

    @GetMapping("/list")
    public R<Page<BotUnmatchedQuestion>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return R.ok(mapper.selectPage(new Page<>(page, size),
            new LambdaQueryWrapper<BotUnmatchedQuestion>()
                .orderByDesc(BotUnmatchedQuestion::getSimilarCount)
                .orderByDesc(BotUnmatchedQuestion::getCreateTime)));
    }

    @PutMapping("/{id}/resolve")
    public R<Void> resolve(@PathVariable Long id) {
        BotUnmatchedQuestion q = mapper.selectById(id);
        if (q != null) { q.setIsResolved(1); mapper.updateById(q); }
        return R.ok();
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
}
