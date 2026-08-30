package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.knowledge.entity.BotKnowledgeDocument;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeSemanticUnitMapper;
import com.feisheng.bot.knowledge.service.StructuredKnowledgeUnitIndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredKnowledgeUnitReviewServiceTest {
    @Test
    void approvesOnlyAfterRevalidatingApprovedEvidenceAndSyncsIndex() {
        Fixture fixture = fixture(chunk("APPROVED", 0));

        StructuredKnowledgeUnitReviewService.ReviewResult result =
            fixture.service.approve(20L);

        assertEquals("APPROVED", fixture.unit.getStatus());
        assertTrue(result.changed());
        assertTrue(result.indexSyncSuccess());
        verify(fixture.unitMapper).transitionReview(
            eq(20L), eq("DRAFT"), eq("APPROVED"), any(), any(), any());
        verify(fixture.indexService).sync();
    }

    @Test
    void rejectsApprovalWhenEvidenceChunkIsStillPending() {
        Fixture fixture = fixture(chunk("PENDING", 0));

        StructuredKnowledgeUnitReviewService.ReviewException error = assertThrows(
            StructuredKnowledgeUnitReviewService.ReviewException.class,
            () -> fixture.service.approve(20L));

        assertEquals(409, error.status());
        verify(fixture.unitMapper, never()).transitionReview(
            any(), any(), any(), any(), any(), any());
        verify(fixture.indexService, never()).sync();
    }

    @Test
    void rejectsApprovalWhenEvidenceChunkWasDeleted() {
        Fixture fixture = fixture(chunk("APPROVED", 1));

        StructuredKnowledgeUnitReviewService.ReviewException error = assertThrows(
            StructuredKnowledgeUnitReviewService.ReviewException.class,
            () -> fixture.service.approve(20L));

        assertEquals(409, error.status());
        assertTrue(error.getMessage().contains("已删除"));
        verify(fixture.unitMapper, never()).transitionReview(
            any(), any(), any(), any(), any(), any());
        verify(fixture.indexService, never()).sync();
    }

    @Test
    void rejectsApprovalWhenEvidenceQuoteNoLongerMatches() {
        BotKnowledgeChunk changed = chunk("APPROVED", 0);
        changed.setContent("已经变化");
        Fixture fixture = fixture(changed);

        assertThrows(StructuredKnowledgeUnitReviewService.ReviewException.class,
            () -> fixture.service.approve(20L));
        verify(fixture.unitMapper, never()).transitionReview(
            any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectionRemovesPreviouslyApprovedUnitFromIndexSnapshot() {
        Fixture fixture = fixture(chunk("APPROVED", 0));
        fixture.unit.setStatus("APPROVED");

        StructuredKnowledgeUnitReviewService.ReviewResult result =
            fixture.service.reject(20L);

        assertEquals("REJECTED", result.status());
        assertEquals("REJECTED", fixture.unit.getStatus());
        verify(fixture.indexService).sync();
    }

    @Test
    void recordsReviewerTimestampAndReason() {
        Fixture fixture = fixture(chunk("APPROVED", 0));

        fixture.service.approve(20L, 77L, "  source verified  ");

        assertEquals(77L, fixture.unit.getReviewedBy());
        assertTrue(fixture.unit.getReviewedAt() != null);
        assertEquals("source verified", fixture.unit.getReviewReason());
        verify(fixture.unitMapper).transitionReview(eq(20L), eq("DRAFT"),
            eq("APPROVED"), eq(77L), any(), eq("source verified"));
    }

    @Test
    void rejectsApprovalWhenOwningDocumentIsNotCompleted() {
        Fixture fixture = fixture(chunk("APPROVED", 0));
        fixture.document.setStatus(1);

        StructuredKnowledgeUnitReviewService.ReviewException error = assertThrows(
            StructuredKnowledgeUnitReviewService.ReviewException.class,
            () -> fixture.service.approve(20L));

        assertEquals(409, error.status());
        verify(fixture.unitMapper, never()).transitionReview(
            any(), any(), any(), any(), any(), any());
        verify(fixture.indexService, never()).sync();
    }

    @Test
    void approvingAUnitInDraftTargetDocumentNeverSynchronizesOnlineIndex() {
        Fixture fixture = fixture(chunk("APPROVED", 0));
        fixture.document.setPublishStatus("DRAFT");

        StructuredKnowledgeUnitReviewService.ReviewResult result =
            fixture.service.approve(20L);

        assertTrue(result.changed());
        assertTrue(result.indexSyncSuccess());
        verify(fixture.indexService, never()).sync();
    }

    @Test
    void concurrentStateChangeFailsWithoutSyncingIndex() {
        Fixture fixture = fixture(chunk("APPROVED", 0));
        when(fixture.unitMapper.transitionReview(
            any(), any(), any(), any(), any(), any())).thenReturn(0);

        StructuredKnowledgeUnitReviewService.ReviewException error = assertThrows(
            StructuredKnowledgeUnitReviewService.ReviewException.class,
            () -> fixture.service.approve(20L));

        assertEquals(409, error.status());
        verify(fixture.indexService, never()).sync();
    }

    @Test
    void batchApprovalDeduplicatesIdsReportsFailuresAndSyncsOnce() {
        Fixture fixture = fixture(chunk("APPROVED", 0));

        StructuredKnowledgeUnitReviewService.BatchReviewResult result =
            fixture.service.approveBatch(List.of(20L, 21L, 20L), 77L, "checked");

        assertEquals(2, result.requested());
        assertEquals(1, result.succeeded());
        assertEquals(1, result.failed());
        assertEquals(1, result.changed());
        assertTrue(result.indexSyncSuccess());
        assertEquals(404, result.items().get(1).errorCode());
        verify(fixture.indexService).sync();
    }

    @Test
    void batchReviewRejectsMoreThanTwoHundredItems() {
        Fixture fixture = fixture(chunk("APPROVED", 0));
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 201)
            .boxed().toList();

        StructuredKnowledgeUnitReviewService.ReviewException error = assertThrows(
            StructuredKnowledgeUnitReviewService.ReviewException.class,
            () -> fixture.service.rejectBatch(ids, 77L, "invalid"));

        assertEquals(400, error.status());
        verify(fixture.indexService, never()).sync();
    }

    private Fixture fixture(BotKnowledgeChunk chunk) {
        BotKnowledgeSemanticUnitMapper unitMapper = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        StructuredKnowledgeUnitIndexService indexService =
            mock(StructuredKnowledgeUnitIndexService.class);
        BotKnowledgeSemanticUnit unit = unit();
        BotKnowledgeDocument document = document();
        when(unitMapper.selectById(20L)).thenReturn(unit);
        when(unitMapper.transitionReview(any(), any(), any(), any(), any(), any()))
            .thenReturn(1);
        when(documentMapper.selectById(5L)).thenReturn(document);
        when(chunkMapper.selectBatchIds(any())).thenReturn(List.of(chunk));
        when(indexService.sync()).thenReturn(new StructuredKnowledgeUnitIndexService.SyncReport(
            true, 2, 1, 1, 0, 0, false, 0, 0, 1,
            "2026-08-04T00:00:00Z", null, null));
        StructuredKnowledgeUnitReviewService service =
            new StructuredKnowledgeUnitReviewService(
                unitMapper, documentMapper, chunkMapper, indexService, new ObjectMapper());
        return new Fixture(service, unitMapper, indexService, unit, document);
    }

    private BotKnowledgeSemanticUnit unit() {
        BotKnowledgeSemanticUnit unit = new BotKnowledgeSemanticUnit();
        unit.setId(20L);
        unit.setDocumentId(5L);
        unit.setStatus("DRAFT");
        unit.setEmbedding("[0.1,0.2]");
        unit.setEvidenceChunkIdsJson("[11]");
        unit.setSourceSpansJson(
            "[{\"chunkId\":11,\"start\":0,\"end\":4,\"quote\":\"证据内容\"}]");
        return unit;
    }

    private BotKnowledgeChunk chunk(String status, int deleted) {
        BotKnowledgeChunk chunk = new BotKnowledgeChunk();
        chunk.setId(11L);
        chunk.setDocumentId(5L);
        chunk.setContent("证据内容");
        chunk.setStatus(status);
        chunk.setDeleted(deleted);
        return chunk;
    }

    private BotKnowledgeDocument document() {
        BotKnowledgeDocument document = new BotKnowledgeDocument();
        document.setId(5L);
        document.setStatus(2);
        document.setSourceScope("KNOWLEDGE");
        document.setPublishStatus("PUBLISHED");
        document.setDeleted(0);
        return document;
    }

    private record Fixture(StructuredKnowledgeUnitReviewService service,
                           BotKnowledgeSemanticUnitMapper unitMapper,
                           StructuredKnowledgeUnitIndexService indexService,
                           BotKnowledgeSemanticUnit unit,
                           BotKnowledgeDocument document) {}
}
