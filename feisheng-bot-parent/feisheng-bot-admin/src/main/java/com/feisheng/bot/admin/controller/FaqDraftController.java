package com.feisheng.bot.admin.controller;

import com.feisheng.bot.admin.service.FaqDraftService;
import com.feisheng.bot.admin.service.FaqRegressionService;
import com.feisheng.bot.common.vo.R;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/unmatched")
public class FaqDraftController {
    private final FaqDraftService draftService;
    private final FaqRegressionService regressionService;

    public FaqDraftController(FaqDraftService draftService,
                              FaqRegressionService regressionService) {
        this.draftService = draftService;
        this.regressionService = regressionService;
    }

    @GetMapping("/faq-draft/list")
    public R<List<FaqDraftService.DraftView>> list(
            @RequestParam(required = false) Long runId) {
        return R.ok(draftService.list(runId));
    }

    @PostMapping("/cluster/{clusterId}/faq-draft")
    public R<FaqDraftService.DraftView> generate(
            @PathVariable Long clusterId,
            @RequestParam(defaultValue = "false") boolean regenerate,
            Authentication authentication) {
        return execute(() -> draftService.generate(clusterId, operatorId(authentication), regenerate));
    }

    @PutMapping("/faq-draft/{id}")
    public R<FaqDraftService.DraftView> update(
            @PathVariable Long id, @RequestBody DraftRequest request,
            Authentication authentication) {
        if (request == null) return R.fail(400, "FAQ草稿内容不能为空");
        return execute(() -> draftService.update(id, request.question(), request.answer(),
            request.keywords(), operatorId(authentication)));
    }

    @PostMapping("/faq-draft/{id}/reject")
    public R<FaqDraftService.DraftView> reject(
            @PathVariable Long id, @RequestBody(required = false) ReviewRequest request,
            Authentication authentication) {
        String reason = request == null ? null : request.reason();
        return execute(() -> draftService.reject(id, reason, operatorId(authentication)));
    }

    @PostMapping("/faq-draft/{id}/publish")
    public R<FaqDraftService.DraftView> publish(
            @PathVariable Long id, Authentication authentication) {
        return execute(() -> draftService.publish(id, operatorId(authentication)));
    }

    @PostMapping("/faq-draft/regression")
    public R<FaqRegressionService.RegressionReport> regression(
            @RequestBody(required = false) RegressionRequest request) {
        List<Long> draftIds = request == null ? null : request.draftIds();
        String promptVersion = request == null ? null : request.promptVersion();
        return execute(() -> regressionService.evaluate(draftIds, promptVersion));
    }

    private <T> R<T> execute(java.util.function.Supplier<T> action) {
        try {
            return R.ok(action.get());
        } catch (FaqDraftService.FaqDraftException error) {
            return R.fail(error.status(), error.getMessage());
        }
    }

    private Long operatorId(Authentication authentication) {
        return authentication == null ? null : (Long) authentication.getPrincipal();
    }

    public record DraftRequest(String question, String answer, String keywords) {}
    public record ReviewRequest(String reason) {}
    public record RegressionRequest(List<Long> draftIds, String promptVersion) {}
}
