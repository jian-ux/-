package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.service.StructuredKnowledgeExtractionService;
import com.feisheng.bot.admin.service.StructuredKnowledgeUnitReviewService;
import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeSemanticUnitMapper;
import com.feisheng.bot.knowledge.service.StructuredKnowledgeUnitIndexService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredKnowledgeUnitAdminControllerTest {
    @Test
    void extractionEndpointPassesExplicitModelSelection() {
        StructuredKnowledgeExtractionService extraction =
            mock(StructuredKnowledgeExtractionService.class);
        StructuredKnowledgeUnitReviewService review =
            mock(StructuredKnowledgeUnitReviewService.class);
        BotKnowledgeSemanticUnitMapper mapper = mock(BotKnowledgeSemanticUnitMapper.class);
        StructuredKnowledgeExtractionService.ExtractionReport report = report("SUCCESS");
        when(extraction.extractDocument(5L, 7L)).thenReturn(report);
        StructuredKnowledgeUnitAdminController controller =
            new StructuredKnowledgeUnitAdminController(
                extraction, review, mapper, mock(StructuredKnowledgeUnitIndexService.class));

        R<StructuredKnowledgeExtractionService.ExtractionReport> response = controller.extract(
            5L, new StructuredKnowledgeUnitAdminController.ExtractionRequest(7L));

        assertEquals(200, response.getCode());
        assertEquals("SUCCESS", response.getData().status());
        verify(extraction).extractDocument(5L, 7L);
    }

    @Test
    void approvalEndpointMapsReviewConflict() {
        StructuredKnowledgeExtractionService extraction =
            mock(StructuredKnowledgeExtractionService.class);
        StructuredKnowledgeUnitReviewService review =
            mock(StructuredKnowledgeUnitReviewService.class);
        BotKnowledgeSemanticUnitMapper mapper = mock(BotKnowledgeSemanticUnitMapper.class);
        when(review.approve(20L, null, null)).thenThrow(
            new StructuredKnowledgeUnitReviewService.ReviewException(409, "证据未审核"));
        StructuredKnowledgeUnitAdminController controller =
            new StructuredKnowledgeUnitAdminController(
                extraction, review, mapper, mock(StructuredKnowledgeUnitIndexService.class));

        R<StructuredKnowledgeUnitReviewService.ReviewResult> response =
            controller.approve(20L, null, null);

        assertEquals(409, response.getCode());
        assertEquals("证据未审核", response.getMsg());
    }

    @Test
    void listEndpointReportsEmbeddingReadinessWithoutReturningVector() {
        StructuredKnowledgeExtractionService extraction =
            mock(StructuredKnowledgeExtractionService.class);
        StructuredKnowledgeUnitReviewService review =
            mock(StructuredKnowledgeUnitReviewService.class);
        BotKnowledgeSemanticUnitMapper mapper = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeSemanticUnit unit = new BotKnowledgeSemanticUnit();
        unit.setId(20L);
        unit.setDocumentId(5L);
        unit.setQuestion("如何签署？");
        unit.setStatement("支持在线签署。");
        unit.setEmbedding("[0.1,0.2]");
        when(mapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<BotKnowledgeSemanticUnit> requested = invocation.getArgument(0);
            requested.setRecords(List.of(unit));
            requested.setTotal(1);
            return requested;
        });
        StructuredKnowledgeUnitAdminController controller =
            new StructuredKnowledgeUnitAdminController(
                extraction, review, mapper, mock(StructuredKnowledgeUnitIndexService.class));

        R<Page<StructuredKnowledgeUnitAdminController.UnitView>> response =
            controller.list(1, 10, 5L, "draft");

        assertEquals(1, response.getData().getTotal());
        assertEquals(10, response.getData().getSize());
        assertTrue(response.getData().getRecords().get(0).embeddingReady());
    }

    @Test
    void indexStatusEndpointReturnsCurrentIndexSnapshot() {
        StructuredKnowledgeExtractionService extraction =
            mock(StructuredKnowledgeExtractionService.class);
        StructuredKnowledgeUnitReviewService review =
            mock(StructuredKnowledgeUnitReviewService.class);
        BotKnowledgeSemanticUnitMapper mapper = mock(BotKnowledgeSemanticUnitMapper.class);
        StructuredKnowledgeUnitIndexService indexService =
            mock(StructuredKnowledgeUnitIndexService.class);
        StructuredKnowledgeUnitIndexService.IndexStatus status =
            new StructuredKnowledgeUnitIndexService.IndexStatus(
                9L, 12, "qdrant", true, null, null);
        when(indexService.status()).thenReturn(status);
        StructuredKnowledgeUnitAdminController controller =
            new StructuredKnowledgeUnitAdminController(extraction, review, mapper, indexService);

        R<StructuredKnowledgeUnitIndexService.IndexStatus> response = controller.indexStatus();

        assertEquals(200, response.getCode());
        assertSame(status, response.getData());
        verify(indexService).status();
    }

    @Test
    void rejectionEndpointRequiresAuditReason() {
        StructuredKnowledgeExtractionService extraction =
            mock(StructuredKnowledgeExtractionService.class);
        StructuredKnowledgeUnitReviewService review =
            mock(StructuredKnowledgeUnitReviewService.class);
        BotKnowledgeSemanticUnitMapper mapper = mock(BotKnowledgeSemanticUnitMapper.class);
        StructuredKnowledgeUnitAdminController controller =
            new StructuredKnowledgeUnitAdminController(
                extraction, review, mapper, mock(StructuredKnowledgeUnitIndexService.class));

        R<StructuredKnowledgeUnitReviewService.ReviewResult> response =
            controller.reject(20L, null, null);

        assertEquals(400, response.getCode());
    }

    @Test
    void batchApprovalEndpointReturnsPerItemSummary() {
        StructuredKnowledgeExtractionService extraction =
            mock(StructuredKnowledgeExtractionService.class);
        StructuredKnowledgeUnitReviewService review =
            mock(StructuredKnowledgeUnitReviewService.class);
        BotKnowledgeSemanticUnitMapper mapper = mock(BotKnowledgeSemanticUnitMapper.class);
        StructuredKnowledgeUnitReviewService.BatchReviewResult result =
            new StructuredKnowledgeUnitReviewService.BatchReviewResult(
                "APPROVE", 2, 1, 1, 1, true, null, List.of(
                    new StructuredKnowledgeUnitReviewService.BatchItemResult(
                        20L, true, "APPROVED", true, null, null),
                    new StructuredKnowledgeUnitReviewService.BatchItemResult(
                        21L, false, null, false, 409, "证据未审核")));
        when(review.approveBatch(List.of(20L, 21L), null, "checked"))
            .thenReturn(result);
        StructuredKnowledgeUnitAdminController controller =
            new StructuredKnowledgeUnitAdminController(
                extraction, review, mapper, mock(StructuredKnowledgeUnitIndexService.class));

        R<StructuredKnowledgeUnitReviewService.BatchReviewResult> response =
            controller.approveBatch(
                new StructuredKnowledgeUnitAdminController.BatchReviewRequest(
                    List.of(20L, 21L), "checked"), null);

        assertEquals(200, response.getCode());
        assertEquals(1, response.getData().succeeded());
        assertEquals(1, response.getData().failed());
        verify(review).approveBatch(List.of(20L, 21L), null, "checked");
    }

    @Test
    void batchRejectionEndpointRequiresAuditReason() {
        StructuredKnowledgeUnitAdminController controller =
            new StructuredKnowledgeUnitAdminController(
                mock(StructuredKnowledgeExtractionService.class),
                mock(StructuredKnowledgeUnitReviewService.class),
                mock(BotKnowledgeSemanticUnitMapper.class),
                mock(StructuredKnowledgeUnitIndexService.class));

        R<StructuredKnowledgeUnitReviewService.BatchReviewResult> response =
            controller.rejectBatch(
                new StructuredKnowledgeUnitAdminController.BatchReviewRequest(
                    List.of(20L), " "), null);

        assertEquals(400, response.getCode());
    }

    private StructuredKnowledgeExtractionService.ExtractionReport report(String status) {
        return new StructuredKnowledgeExtractionService.ExtractionReport(
            5L, status, "ok", "source", "extract-small", 1, 1, 1, 0, 1, 1,
            new StructuredKnowledgeExtractionService.PersistSummary(
                1, 1, 0, 0, 0, List.of(20L)),
            List.of(), List.of());
    }
}
