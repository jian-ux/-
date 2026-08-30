package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeConflict;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeMigrationReviewServiceTest {
    @Test
    void blocksConfirmationForAnUnreviewedUnit() {
        Fixture fixture = fixture(unit("DRAFT", "[0.1]", "[11]"));

        var report = fixture.service.confirmDocument(1L, 7L);

        assertFalse(report.passed());
        assertEquals(1, report.unreviewedUnits());
        verify(fixture.jobMapper, never()).updateById(any(BotKnowledgeMigrationJob.class));
    }

    @Test
    void blocksConfirmationForAnApprovedUnitWithoutVector() {
        Fixture fixture = fixture(unit("APPROVED", "", "[11]"));

        var report = fixture.service.confirmDocument(1L, 7L);

        assertFalse(report.passed());
        assertTrue(report.blockers().stream().anyMatch(message -> message.contains("向量")));
        verify(fixture.jobMapper, never()).updateById(any(BotKnowledgeMigrationJob.class));
    }

    @Test
    void blocksConfirmationForAnApprovedUnitWithoutEvidence() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[]"));

        var report = fixture.service.confirmDocument(1L, 7L);

        assertFalse(report.passed());
        assertTrue(report.blockers().stream().anyMatch(message -> message.contains("证据")));
        verify(fixture.jobMapper, never()).updateById(any(BotKnowledgeMigrationJob.class));
    }

    @Test
    void blocksConfirmationForPendingBlockingAndWarningConflicts() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));
        when(fixture.conflictMapper.selectList(any())).thenReturn(List.of(
            conflict(10L, "BLOCKING"), conflict(11L, "WARNING")));

        var report = fixture.service.confirmDocument(1L, 7L);

        assertFalse(report.passed());
        assertEquals(1, report.blockingConflicts());
        assertEquals(1, report.warningConflicts());
    }

    @Test
    void blocksConfirmationWhenSourceHashIsStale() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));
        fixture.job.setSourceContentHash("stale");

        var report = fixture.service.confirmDocument(1L, 7L);

        assertFalse(report.passed());
        assertTrue(report.blockers().stream().anyMatch(message -> message.contains("过期")));
    }

    @Test
    void zeroConflictJobRemainsReviewRequiredUntilExplicitConfirmation() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));

        assertEquals("REVIEW_REQUIRED", fixture.job.getStatus());
        var report = fixture.service.confirmDocument(1L, 7L);

        assertTrue(report.passed());
        assertEquals("READY_TO_SWITCH", fixture.job.getStatus());
        assertEquals(7L, fixture.job.getReviewerId());
        assertNotNull(fixture.job.getReviewedAt());
    }

    @Test
    void repeatedConfirmationReturnsTheRecordedGateReportWithoutReplacingReviewer() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));

        var first = fixture.service.confirmDocument(1L, 7L);
        var second = fixture.service.confirmDocument(1L, 8L);

        assertEquals(first, second);
        assertEquals(7L, fixture.job.getReviewerId());
        verify(fixture.jobMapper).updateById(fixture.job);
    }

    @Test
    void refusesConfirmationOutsideReviewRequired() {
        Fixture fixture = fixture(unit("APPROVED", "[0.1]", "[11]"));
        fixture.job.setStatus("FAILED");

        var error = assertThrows(KnowledgeMigrationReviewService.ReviewException.class,
            () -> fixture.service.confirmDocument(1L, 7L));

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
        when(unitMapper.selectList(any())).thenReturn(List.of(unit));
        when(conflictMapper.selectList(any())).thenReturn(List.of());
        when(chunkMapper.selectList(any())).thenReturn(List.of(sourceChunk()));
        return new Fixture(new KnowledgeMigrationReviewService(jobMapper, conflictMapper, unitMapper,
            documentMapper, chunkMapper, new ObjectMapper()), jobMapper, conflictMapper, job);
    }

    private BotKnowledgeMigrationJob job() {
        BotKnowledgeMigrationJob job = new BotKnowledgeMigrationJob();
        job.setId(1L);
        job.setSourceDocumentId(2L);
        job.setTargetDocumentId(3L);
        job.setStatus("REVIEW_REQUIRED");
        job.setSourceContentHash(EmbeddingMetadataUtil.contentHash("11\u00000\u0000source\u0001"));
        return job;
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
                           BotKnowledgeMigrationJob job) {}
}
