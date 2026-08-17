package com.feisheng.bot.admin.controller;

import com.feisheng.bot.admin.service.KnowledgeQualityAuditService;
import com.feisheng.bot.common.vo.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/knowledge-quality")
public class KnowledgeQualityAuditController {
    private final KnowledgeQualityAuditService auditService;

    public KnowledgeQualityAuditController(KnowledgeQualityAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/audit")
    public R<KnowledgeQualityAuditService.AuditReport> audit() {
        return R.ok(auditService.audit());
    }
}
