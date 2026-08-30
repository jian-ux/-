package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeConflict;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.entity.BotKnowledgeMigrationJob;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeConflictMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeMigrationJobMapper;
import com.feisheng.bot.common.util.EmbeddingMetadataUtil;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeSemanticUnitMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeMigrationReviewServiceTest {
    @Test
    void blocksConfirmationForAnUnreviewedUnit() {
        Fixture fixture = fixture(unit("DRAFT", "[0.1]", "[11]"));

        var report = fixture.service.confirmDocument(1L, new KnowledgeMigrationReviewService.ConfirmationRequest("quality review"), 7L);

        assertFalse(report.passed());
        assertEquals(1, report.unreviewedUnits());
        verify(fixture.jobMapper, never()).updateById(any(BotKnowledgeMigrationJob.class));
    }

    @Test
    void blocksConfirmationForAnApprovedUnitWithoutVector() {
        Fixture fixture = fixture(unit("APPROVED", "", "[11]"));

        var report = fixture.service.confirmDocument(1L, new KnowledgeMigrationReviewService.ConfirmationRequest("quality review"), 7L);

        assertFalse(report.passed());
        assertTrue(report.blockers().stream().anyMatch(message -> message.contains("向量")));
        verify(fixture.jobMapper, never()).updateById(any(BotKnowledgeMigrationJob.class));
    }

    @Test
    void blocksConfirmationForAnApprovedUnitWithoutEvidence() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[]"));

        var report = fixture.service.confirmDocument(1L, new KnowledgeMigrationReviewService.ConfirmationRequest("quality review"), 7L);

        assertFalse(report.passed());
        assertTrue(report.blockers().stream().anyMatch(message -> message.contains("证据")));
        verify(fixture.jobMapper, never()).updateById(any(BotKnowledgeMigrationJob.class));
    }

    @Test
    void blocksConfirmationForMalformedEmbeddingJsonAndNonFiniteValues() {
        Fixture malformed = fixture(unit("APPROVED", "not-json", "[11]"));
        assertFalse(malformed.service.confirmDocument(1L, new KnowledgeMigrationReviewService.ConfirmationRequest("quality review"), 7L).passed());

        Fixture nonFinite = fixture(unit("APPROVED", "[NaN]", "[11]"));
        assertFalse(nonFinite.service.confirmDocument(1L, new KnowledgeMigrationReviewService.ConfirmationRequest("quality review"), 7L).passed());
    }

    @Test
    void blocksConfirmationForInvalidEvidenceSpansAndLowExtractionConfidence() {
        BotKnowledgeSemanticUnit unit = unit("APPROVED", "[0.1]", "[\"not-a-chunk\"]");
        unit.setSourceSpansJson("[]");
        unit.setExtractionConfidence(0.2d);
        Fixture fixture = fixture(unit);

        var report = fixture.service.confirmDocument(1L, new KnowledgeMigrationReviewService.ConfirmationRequest("quality review"), 7L);

        assertFalse(report.passed());
        assertTrue(report.blockers().stream().anyMatch(message -> message.contains("质量")));
    }

    @Test
    void blocksConfirmationWhenEvidenceSpanOffsetsAreFractionalEvenIfTruncationMatchesQuote() {
        BotKnowledgeSemanticUnit unit = unit("APPROVED", "[0.1]", "[11]");
        unit.setSourceSpansJson(
            "[{\"chunkId\":11,\"start\":0.5,\"end\":6.5,\"quote\":\"source\"}]");
        Fixture fixture = fixture(unit);

        var report = fixture.service.confirmDocument(1L,
            new KnowledgeMigrationReviewService.ConfirmationRequest("quality review"), 7L);

        assertFalse(report.passed());
        assertTrue(report.blockers().stream().anyMatch(message -> message.contains("质量")));
        verify(fixture.jobMapper, never()).confirm(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void doesNotAllowUnknownDeterministicBlockerToBeOverridden() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));
        BotKnowledgeConflict conflict = conflict(9L, "BLOCKING");
        conflict.setCandidateUnitId(0L);
        conflict.setConflictType("VECTOR");
        conflict.setRuleResult("{\"judgment\":\"UNKNOWN\"}");
        when(fixture.conflictMapper.selectById(9L)).thenReturn(conflict);

        var error = assertThrows(KnowledgeMigrationReviewService.ReviewException.class,
            () -> fixture.service.resolveConflict(1L, 9L,
                new KnowledgeMigrationReviewService.ResolutionRequest("NOT_CONFLICT", "reason"), 7L));

        assertEquals(409, error.status());
        verify(fixture.conflictMapper, never()).updateById(conflict);
    }

    @Test
    void doesNotAllowUnknownDeterministicWarningToBeOverridden() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));
        BotKnowledgeConflict conflict = conflict(9L, "WARNING");
        conflict.setCandidateUnitId(100L);
        conflict.setConflictType("FACT");
        conflict.setRuleResult("{\"judgment\":\"UNKNOWN\"}");
        when(fixture.conflictMapper.selectById(9L)).thenReturn(conflict);

        var error = assertThrows(KnowledgeMigrationReviewService.ReviewException.class,
            () -> fixture.service.resolveConflict(1L, 9L,
                new KnowledgeMigrationReviewService.ResolutionRequest("MERGE", "reason"), 7L));

        assertEquals(409, error.status());
        verify(fixture.conflictMapper, never()).updateById(conflict);
    }

    @Test
    void confirmationRequiresExactCompletedDraftTargetBoundToTheJob() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));
        when(fixture.documentMapper.selectById(3L)).thenReturn(null);
        assertFalse(fixture.service.confirmDocument(1L, new KnowledgeMigrationReviewService.ConfirmationRequest("quality review"), 7L).passed());

        BotKnowledgeDocument wrongState = targetDocument();
        wrongState.setPublishStatus("PUBLISHED");
        when(fixture.documentMapper.selectById(3L)).thenReturn(wrongState);
        assertFalse(fixture.service.confirmDocument(1L, new KnowledgeMigrationReviewService.ConfirmationRequest("quality review"), 7L).passed());

        BotKnowledgeDocument wrongVersion = targetDocument();
        wrongVersion.setDocumentVersion(99);
        fixture.job.setTargetVersionId(null);
        when(fixture.documentMapper.selectById(3L)).thenReturn(wrongVersion);
        assertFalse(fixture.service.confirmDocument(1L, new KnowledgeMigrationReviewService.ConfirmationRequest("quality review"), 7L).passed());
    }

    @Test
    void confirmationRequiresDurableNonBlankReason() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));
        var error = assertThrows(KnowledgeMigrationReviewService.ReviewException.class,
            () -> fixture.service.confirmDocument(1L, new KnowledgeMigrationReviewService.ConfirmationRequest("  "), 7L));
        assertEquals(400, error.status());
    }

    @Test
    void concurrentConfirmationReturnsPersistedIdempotentReport() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));
        BotKnowledgeMigrationJob persisted = job();
        persisted.setStatus("READY_TO_SWITCH");
        persisted.setReviewerId(7L);
        when(fixture.jobMapper.updateById(any(BotKnowledgeMigrationJob.class))).thenReturn(0);
        when(fixture.jobMapper.selectById(1L)).thenReturn(fixture.job, persisted);

        var report = fixture.service.confirmDocument(1L, new KnowledgeMigrationReviewService.ConfirmationRequest("racing reviewer"), 8L);

        assertTrue(report.passed());
        assertEquals(7L, persisted.getReviewerId());
        verify(fixture.jobMapper).confirm(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void blocksConfirmationForPendingBlockingAndWarningConflicts() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));
        when(fixture.conflictMapper.selectList(any())).thenReturn(List.of(
            conflict(10L, "BLOCKING"), conflict(11L, "WARNING")));

        var report = fixture.service.confirmDocument(1L, new KnowledgeMigrationReviewService.ConfirmationRequest("quality review"), 7L);

        assertFalse(report.passed());
        assertEquals(1, report.blockingConflicts());
        assertEquals(1, report.warningConflicts());
    }

    @Test
    void blocksConfirmationWhenSourceHashIsStale() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));
        fixture.job.setSourceContentHash("stale");

        var report = fixture.service.confirmDocument(1L, new KnowledgeMigrationReviewService.ConfirmationRequest("quality review"), 7L);

        assertFalse(report.passed());
        assertTrue(report.blockers().stream().anyMatch(message -> message.contains("过期")));
    }

    @Test
    void zeroConflictJobRemainsReviewRequiredUntilExplicitConfirmation() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));

        assertEquals("REVIEW_REQUIRED", fixture.job.getStatus());
        var report = fixture.service.confirmDocument(1L, new KnowledgeMigrationReviewService.ConfirmationRequest("quality review"), 7L);

        assertTrue(report.passed());
        assertEquals("READY_TO_SWITCH", fixture.job.getStatus());
        assertEquals(7L, fixture.job.getReviewerId());
        assertNotNull(fixture.job.getReviewedAt());
        assertEquals("quality review", fixture.job.getReviewReason());
        assertTrue(fixture.job.getReviewAuditJson().contains("\"before\""));
        assertTrue(fixture.job.getReviewAuditJson().contains("\"after\""));
    }

    @Test
    void repeatedConfirmationReturnsTheRecordedGateReportWithoutReplacingReviewer() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));

        var first = fixture.service.confirmDocument(1L, new KnowledgeMigrationReviewService.ConfirmationRequest("quality review"), 7L);
        var second = fixture.service.confirmDocument(1L, new KnowledgeMigrationReviewService.ConfirmationRequest("quality review"), 8L);

        assertEquals(first, second);
        assertEquals(7L, fixture.job.getReviewerId());
        verify(fixture.jobMapper).confirm(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void refusesConfirmationOutsideReviewRequired() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));
        fixture.job.setStatus("FAILED");

        var error = assertThrows(KnowledgeMigrationReviewService.ReviewException.class,
            () -> fixture.service.confirmDocument(1L, new KnowledgeMigrationReviewService.ConfirmationRequest("quality review"), 7L));

        assertEquals(409, error.status());
    }

    @Test
    void recordsEachSupportedConflictResolutionWithAuditSnapshot() throws Exception {
        for (String resolution : List.of("ADOPT_TARGET", "KEEP_SOURCE", "MERGE", "SPLIT_SCOPE", "NOT_CONFLICT")) {
            Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));
            BotKnowledgeConflict conflict = conflict(9L, "BLOCKING");
            conflict.setRuleResult("{\"source\":\"old\"}");
            when(fixture.conflictMapper.selectById(9L)).thenReturn(conflict);

            var result = fixture.service.resolveConflict(1L, 9L,
                new KnowledgeMigrationReviewService.ResolutionRequest(resolution, "  reviewed  "), 7L);

            assertEquals(resolution, result.resolution());
            assertEquals("NOT_CONFLICT".equals(resolution) ? "NOT_CONFLICT" : "RESOLVED", result.status());
            assertEquals(7L, conflict.getReviewerId());
            assertEquals("reviewed", conflict.getResolutionNote());
            JsonNode audit = new ObjectMapper().readTree(conflict.getRuleResult()).path("resolutionAudit");
            assertEquals("PENDING", audit.path("before").path("status").asText());
            assertEquals(resolution, audit.path("after").path("resolution").asText());
            assertEquals("reviewed", audit.path("reason").asText());
        }
    }

    @Test
    void rejectsResolutionForConflictFromAnotherJobOrMissingReviewer() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));
        BotKnowledgeConflict otherJob = conflict(9L, "BLOCKING");
        otherJob.setMigrationJobId(2L);
        when(fixture.conflictMapper.selectById(9L)).thenReturn(otherJob);

        assertEquals(404, assertThrows(KnowledgeMigrationReviewService.ReviewException.class,
            () -> fixture.service.resolveConflict(1L, 9L,
                new KnowledgeMigrationReviewService.ResolutionRequest("MERGE", "reason"), 7L)).status());

        BotKnowledgeConflict owned = conflict(10L, "BLOCKING");
        when(fixture.conflictMapper.selectById(10L)).thenReturn(owned);
        assertEquals(403, assertThrows(KnowledgeMigrationReviewService.ReviewException.class,
            () -> fixture.service.resolveConflict(1L, 10L,
                new KnowledgeMigrationReviewService.ResolutionRequest("MERGE", "reason"), null)).status());
        verify(fixture.conflictMapper, never()).updateById(owned);
    }

    @Test
    void acceptsOnlyNotConflictForInformationalConflicts() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));
        BotKnowledgeConflict conflict = conflict(9L, "INFO");
        when(fixture.conflictMapper.selectById(9L)).thenReturn(conflict);

        var error = assertThrows(KnowledgeMigrationReviewService.ReviewException.class,
            () -> fixture.service.resolveConflict(1L, 9L,
                new KnowledgeMigrationReviewService.ResolutionRequest("MERGE", "reason"), 7L));

        assertEquals(409, error.status());
        verify(fixture.conflictMapper, never()).updateById(conflict);
    }

    private Fixture fixture(BotKnowledgeSemanticUnit unit) {
        BotKnowledgeMigrationJobMapper jobMapper = mock(BotKnowledgeMigrationJobMapper.class);
        BotKnowledgeConflictMapper conflictMapper = mock(BotKnowledgeConflictMapper.class);
        BotKnowledgeSemanticUnitMapper unitMapper = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeMigrationJob job = job();
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(documentMapper.selectById(3L)).thenReturn(targetDocument());
        when(unitMapper.selectList(any())).thenReturn(List.of(unit));
        when(conflictMapper.selectList(any())).thenReturn(List.of());
        when(chunkMapper.selectList(any())).thenReturn(List.of(sourceChunk()));
        when(jobMapper.confirm(any(), anyLong(), any(), any(), any(), any())).thenReturn(1);
        return new Fixture(new KnowledgeMigrationReviewService(jobMapper, conflictMapper, unitMapper,
            documentMapper, chunkMapper, new ObjectMapper()), jobMapper, conflictMapper, documentMapper, job);
    }

    private BotKnowledgeMigrationJob job() {
        BotKnowledgeMigrationJob job = new BotKnowledgeMigrationJob();
        job.setId(1L);
        job.setSourceDocumentId(2L);
        job.setTargetDocumentId(3L);
        job.setTargetVersionId(1L);
        job.setKnowledgeSetKey("set-a");
        job.setStatus("REVIEW_REQUIRED");
        job.setSourceContentHash(EmbeddingMetadataUtil.contentHash("11\u00000\u0000source\u0001"));
        return job;
    }

    private BotKnowledgeDocument targetDocument() {
        BotKnowledgeDocument document = new BotKnowledgeDocument();
        document.setId(3L);
        document.setStatus(2);
        document.setPublishStatus("DRAFT");
        document.setSourceScope("KNOWLEDGE");
        document.setKnowledgeSetKey("set-a");
        document.setDocumentVersion(1);
        return document;
    }

    private BotKnowledgeChunk sourceChunk() {
        BotKnowledgeChunk chunk = new BotKnowledgeChunk();
        chunk.setId(11L);
        chunk.setChunkIndex(0);
        chunk.setContent("source");
        return chunk;
    }

    private BotKnowledgeSemanticUnit unit(String status, String embedding, String evidence) {
        BotKnowledgeSemanticUnit unit = new BotKnowledgeSemanticUnit();
        unit.setId(30L);
        unit.setDocumentId(3L);
        unit.setStatus(status);
        unit.setEmbedding(embedding);
        unit.setEvidenceChunkIdsJson(evidence);
        unit.setSourceSpansJson("[{\"chunkId\":11,\"start\":0,\"end\":6,\"quote\":\"source\"}]");
        unit.setExtractionConfidence(1.0d);
        unit.setDeleted(0);
        return unit;
    }

    private BotKnowledgeConflict conflict(Long id, String severity) {
        BotKnowledgeConflict conflict = new BotKnowledgeConflict();
        conflict.setId(id);
        conflict.setMigrationJobId(1L);
        conflict.setStatus("PENDING");
        conflict.setSeverity(severity);
        conflict.setResolution("UNRESOLVED");
        return conflict;
    }

    private record Fixture(KnowledgeMigrationReviewService service,
                           BotKnowledgeMigrationJobMapper jobMapper,
                           BotKnowledgeConflictMapper conflictMapper,
                           BotKnowledgeDocumentMapper documentMapper,
                           BotKnowledgeMigrationJob job) {}
}
