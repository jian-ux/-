package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeQualityAuditServiceTest {
    private final KnowledgeQualityAuditService service =
        new KnowledgeQualityAuditService(null, null);

    @Test
    void reportsExactAndCrossQuestionConflictsWithoutChangingKnowledge() {
        BotKnowledgeDocument document = publishedDocument(63L, "点签问答库");
        List<BotKnowledgeChunk> chunks = List.of(
            qa(1L, "点签可以在哪里使用？",
                "点签支持微信小程序和网页端，不支持独立的手机APP。"),
            qa(2L, "签署必须使用电脑吗？手机可以吗？",
                "手机、电脑都支持，可通过手机 APP、网页端或小程序完成签署。"),
            qa(3L, "如何申请开票？", "请在账户中心提交开票资料。"),
            qa(4L, "如何申请开票?", "请联系客户经理申请开票。"));

        KnowledgeQualityAuditService.AuditReport report =
            service.audit(chunks, List.of(document), new Date());

        assertEquals(4, report.qaCount());
        assertTrue(report.findings().stream()
            .anyMatch(finding -> "EXACT_QUESTION_CONFLICT".equals(finding.code())));
        KnowledgeQualityAuditService.Finding mobileConflict = report.findings().stream()
            .filter(finding -> "SEMANTIC_CONFLICT_CANDIDATE".equals(finding.code()))
            .filter(finding -> finding.evidence().stream()
                .anyMatch(evidence -> evidence.chunkId() == 1L))
            .findFirst().orElseThrow();
        assertEquals(2, mobileConflict.evidence().size());
        assertTrue(mobileConflict.message().contains("手机APP"));
    }

    @Test
    void flagsTimeSensitiveInternalCompositeAndRiskyDirectAnswers() {
        BotKnowledgeDocument document = publishedDocument(63L, "点签问答库");
        BotKnowledgeChunk price = qa(10L, "专业版多少钱？",
            "专业版1999元。（衡量：强势客户可特殊处理）后续怎么退款？请联系客服。");
        price.setDirectAnswerEnabled(1);

        KnowledgeQualityAuditService.AuditReport report =
            service.audit(List.of(price), List.of(document), new Date());

        assertTrue(report.byCode().containsKey("PRICE_REVIEW_REQUIRED"));
        assertTrue(report.byCode().containsKey("INTERNAL_NOTE_EXPOSURE"));
        assertTrue(report.byCode().containsKey("COMPOSITE_QA_ANSWER"));
        assertTrue(report.byCode().containsKey("RISKY_DIRECT_ANSWER"));
    }

    @Test
    void doesNotTreatNoNeedAsOppositeToSupportedCapability() {
        BotKnowledgeDocument document = publishedDocument(63L, "点签问答库");
        List<BotKnowledgeChunk> chunks = List.of(
            qa(11L, "点签可以做什么？", "点签支持在线签署电子合同。"),
            qa(12L, "签合同需要双方见面吗？", "无需面对面签署，可在线完成。"));

        KnowledgeQualityAuditService.AuditReport report =
            service.audit(chunks, List.of(document), new Date());

        assertTrue(report.findings().stream()
            .noneMatch(finding -> "SEMANTIC_CONFLICT_CANDIDATE".equals(finding.code())));
    }

    @Test
    void doesNotTreatTamperResistanceLanguageAsNegativeClaim() {
        BotKnowledgeDocument document = publishedDocument(63L, "点签问答库");
        List<BotKnowledgeChunk> chunks = List.of(
            qa(13L, "电子合同存证会记录审批吗？",
                "系统记录审批流程并保证结果不可抵赖。"),
            qa(14L, "存证是否支持审批流追溯？",
                "支持审批流追溯，可以还原审批结果。"));

        KnowledgeQualityAuditService.AuditReport report =
            service.audit(chunks, List.of(document), new Date());

        assertTrue(report.findings().stream()
            .noneMatch(finding -> "SEMANTIC_CONFLICT_CANDIDATE".equals(finding.code())));
    }

    @Test
    void doesNotTreatDifferentSigningStatesAsConflict() {
        BotKnowledgeDocument document = publishedDocument(63L, "点签问答库");
        List<BotKnowledgeChunk> chunks = List.of(
            qa(15L, "合同发起后还未签署，发现附件漏传了，能补充上传附件吗？",
                "未签署时可以补充附件；已有一方签署后不能再修改。"),
            qa(16L, "合同签署完成后，发现附件漏传了，能补充上传附件吗？",
                "已完成签署的合同无法直接补充附件。"));

        KnowledgeQualityAuditService.AuditReport report =
            service.audit(chunks, List.of(document), new Date());

        assertTrue(report.findings().stream()
            .noneMatch(finding -> "SEMANTIC_CONFLICT_CANDIDATE".equals(finding.code())));
    }

    @Test
    void excludesDraftExpiredAndUnapprovedKnowledge() {
        BotKnowledgeDocument draft = publishedDocument(70L, "草稿");
        draft.setPublishStatus(KnowledgeDocumentReleaseService.DRAFT);
        BotKnowledgeDocument expired = publishedDocument(71L, "已过期");
        expired.setEffectiveTo(new Date(System.currentTimeMillis() - 60_000));
        BotKnowledgeChunk unapproved = qa(20L, "价格？", "1999元");
        unapproved.setDocumentId(72L);
        unapproved.setStatus("PENDING");

        KnowledgeQualityAuditService.AuditReport report = service.audit(
            List.of(qaForDocument(21L, 70L), qaForDocument(22L, 71L), unapproved),
            List.of(draft, expired, publishedDocument(72L, "有效文档")), new Date());

        assertEquals(0, report.qaCount());
        assertTrue(report.findings().isEmpty());
    }

    private BotKnowledgeDocument publishedDocument(Long id, String title) {
        BotKnowledgeDocument value = new BotKnowledgeDocument();
        value.setId(id);
        value.setTitle(title);
        value.setStatus(2);
        value.setPublishStatus(KnowledgeDocumentReleaseService.PUBLISHED);
        return value;
    }

    private BotKnowledgeChunk qa(Long id, String question, String answer) {
        BotKnowledgeChunk value = new BotKnowledgeChunk();
        value.setId(id);
        value.setDocumentId(63L);
        value.setContentType("QA");
        value.setStatus("APPROVED");
        value.setDeleted(0);
        value.setQaQuestion(question);
        value.setQaAnswer(answer);
        value.setDirectAnswerEnabled(0);
        return value;
    }

    private BotKnowledgeChunk qaForDocument(Long id, Long documentId) {
        BotKnowledgeChunk value = qa(id, "点签价格是多少？", "专业版1999元。");
        value.setDocumentId(documentId);
        return value;
    }
}
