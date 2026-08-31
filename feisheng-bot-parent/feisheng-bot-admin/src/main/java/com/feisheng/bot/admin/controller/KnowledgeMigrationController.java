package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotKnowledgeConflict;
import com.feisheng.bot.admin.mapper.BotKnowledgeConflictMapper;
import com.feisheng.bot.admin.service.KnowledgeMigrationJobService;
import com.feisheng.bot.admin.service.KnowledgeMigrationReviewService;
import com.feisheng.bot.common.vo.R;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/knowledge")
public class KnowledgeMigrationController {
    private final KnowledgeMigrationJobService jobService;
    private final KnowledgeMigrationReviewService reviewService;
    private final BotKnowledgeConflictMapper conflictMapper;
    private final com.feisheng.bot.admin.service.KnowledgeDocumentReleaseService releaseService;

    @Autowired
    public KnowledgeMigrationController(KnowledgeMigrationJobService jobService,
                                        KnowledgeMigrationReviewService reviewService,
                                        BotKnowledgeConflictMapper conflictMapper,
                                        com.feisheng.bot.admin.service.KnowledgeDocumentReleaseService releaseService) {
        this.jobService = jobService;
        this.reviewService = reviewService;
        this.conflictMapper = conflictMapper;
        this.releaseService = releaseService;
    }

    public KnowledgeMigrationController(KnowledgeMigrationJobService jobService,
                                        KnowledgeMigrationReviewService reviewService,
                                        BotKnowledgeConflictMapper conflictMapper) {
        this(jobService, reviewService, conflictMapper, null);
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

    @GetMapping("/migrations")
    public R<List<KnowledgeMigrationJobService.MigrationJobView>> list() {
        return R.ok(jobService.list());
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
    public R<Page<BotKnowledgeConflict>> conflicts(@PathVariable Long id,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long size) {
        try {
            jobService.get(id);
            long safePage = Math.max(1, page);
            long safeSize = Math.min(100, Math.max(1, size));
            return R.ok(conflictMapper.selectPage(new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<BotKnowledgeConflict>()
                .eq(BotKnowledgeConflict::getMigrationJobId, id)
                .orderByDesc(BotKnowledgeConflict::getSeverity)
                .orderByAsc(BotKnowledgeConflict::getId)));
        } catch (KnowledgeMigrationJobService.MigrationJobException e) {
            return R.fail(e.status(), e.getMessage());
        }
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
                                                                  @RequestBody(required = false)
                                                                  KnowledgeMigrationReviewService.ConfirmationRequest request,
                                                                  Authentication authentication) {
        try {
            return R.ok(reviewService.confirmDocument(id, request, operatorId(authentication)));
        } catch (KnowledgeMigrationReviewService.ReviewException e) {
            return R.fail(e.status(), e.getMessage());
        }
    }

    @PostMapping("/migrations/{id}/switch")
    public R<com.feisheng.bot.admin.service.KnowledgeDocumentReleaseService.ReleaseResult> switchVersion(
            @PathVariable Long id, Authentication authentication) {
        try {
            if (releaseService == null) return R.fail(501, "迁移发布组件未配置");
            return R.ok(releaseService.switchMigration(id, operatorId(authentication)));
        } catch (com.feisheng.bot.admin.service.KnowledgeDocumentReleaseService.ReleaseException e) {
            return R.fail(e.status(), e.getMessage());
        }
    }

    @PostMapping("/sets/{knowledgeSetKey}/rollback")
    public R<com.feisheng.bot.admin.service.KnowledgeDocumentReleaseService.ReleaseResult> rollback(
            @PathVariable String knowledgeSetKey,
            @RequestParam(required = false) Long targetDocumentId,
            @RequestParam(required = false) String reason,
            Authentication authentication) {
        try {
            if (releaseService == null) return R.fail(501, "迁移发布组件未配置");
            return R.ok(releaseService.rollback(knowledgeSetKey, targetDocumentId,
                operatorId(authentication), reason));
        } catch (com.feisheng.bot.admin.service.KnowledgeDocumentReleaseService.ReleaseException e) {
            return R.fail(e.status(), e.getMessage());
        }
    }

    public record CreateRequest(Long sourceDocumentId) {}

    private Long operatorId(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof Long id ? id : null;
    }
}
