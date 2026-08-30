package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.BotKnowledgeConflict;
import com.feisheng.bot.admin.mapper.BotKnowledgeConflictMapper;
import com.feisheng.bot.admin.service.KnowledgeMigrationJobService;
import com.feisheng.bot.admin.service.KnowledgeMigrationReviewService;
import com.feisheng.bot.common.vo.R;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/knowledge")
public class KnowledgeMigrationController {
    private final KnowledgeMigrationJobService jobService;
    private final KnowledgeMigrationReviewService reviewService;
    private final BotKnowledgeConflictMapper conflictMapper;

    public KnowledgeMigrationController(KnowledgeMigrationJobService jobService,
                                        KnowledgeMigrationReviewService reviewService,
                                        BotKnowledgeConflictMapper conflictMapper) {
        this.jobService = jobService;
        this.reviewService = reviewService;
        this.conflictMapper = conflictMapper;
    }

    @PostMapping("/migrations")
    public R<KnowledgeMigrationJobService.MigrationJobView> create(@RequestBody CreateRequest request,
                                                                     Authentication authentication) {
        try {
            if (request == null) return R.fail(400, "缺少迁移请求");
            return R.ok(jobService.create(request.sourceDocumentId(), operatorId(authentication)));
        } catch (KnowledgeMigrationJobService.MigrationJobException e) {
            return R.fail(e.status(), e.getMessage());
        }
    }

    @GetMapping("/migrations/{id}")
    public R<KnowledgeMigrationJobService.MigrationJobView> get(@PathVariable Long id) {
        try {
            return R.ok(jobService.get(id));
        } catch (KnowledgeMigrationJobService.MigrationJobException e) {
            return R.fail(e.status(), e.getMessage());
        }
    }

    @PostMapping("/migrations/{id}/retry")
    public R<KnowledgeMigrationJobService.MigrationJobView> retry(@PathVariable Long id,
                                                                   Authentication authentication) {
        try {
            return R.ok(jobService.retry(id, operatorId(authentication)));
        } catch (KnowledgeMigrationJobService.MigrationJobException e) {
            return R.fail(e.status(), e.getMessage());
        }
    }

    @GetMapping("/migrations/{id}/conflicts")
    public R<List<BotKnowledgeConflict>> conflicts(@PathVariable Long id) {
        return R.ok(conflictMapper.selectList(new LambdaQueryWrapper<BotKnowledgeConflict>()
            .eq(BotKnowledgeConflict::getMigrationJobId, id)
            .orderByDesc(BotKnowledgeConflict::getSeverity)
            .orderByAsc(BotKnowledgeConflict::getId)));
    }

    @PostMapping("/migrations/{id}/conflicts/{conflictId}/resolve")
    public R<KnowledgeMigrationReviewService.ConflictResolution> resolve(
            @PathVariable Long id, @PathVariable Long conflictId,
            @RequestBody KnowledgeMigrationReviewService.ResolutionRequest request,
            Authentication authentication) {
        try {
            return R.ok(reviewService.resolveConflict(id, conflictId, request, operatorId(authentication)));
        } catch (KnowledgeMigrationReviewService.ReviewException e) {
            return R.fail(e.status(), e.getMessage());
        }
    }

    @PostMapping("/migrations/{id}/review/confirm")
    public R<KnowledgeMigrationReviewService.GateReport> confirm(@PathVariable Long id,
                                                                  Authentication authentication) {
        try {
            return R.ok(reviewService.confirmDocument(id, operatorId(authentication)));
        } catch (KnowledgeMigrationReviewService.ReviewException e) {
            return R.fail(e.status(), e.getMessage());
        }
    }

    @PostMapping("/migrations/{id}/switch")
    public R<Void> switchVersion(@PathVariable Long id) {
        return R.fail(501, "文档切换尚未接入发布工作流");
    }

    @PostMapping("/sets/{knowledgeSetKey}/rollback")
    public R<Void> rollback(@PathVariable String knowledgeSetKey) {
        return R.fail(501, "文档回滚尚未接入发布工作流");
    }

    public record CreateRequest(Long sourceDocumentId) {}

    private Long operatorId(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof Long id ? id : null;
    }
}
