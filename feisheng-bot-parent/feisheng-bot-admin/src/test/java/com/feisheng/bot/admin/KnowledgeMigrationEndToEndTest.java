package com.feisheng.bot.admin;

import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeConflict;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.entity.BotKnowledgeMigrationJob;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeConflictMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeMigrationJobMapper;
import com.feisheng.bot.admin.service.KnowledgeDocumentReleaseService;
import com.feisheng.bot.admin.service.KnowledgeMigrationReviewService;
import com.feisheng.bot.common.util.EmbeddingMetadataUtil;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeSemanticUnitMapper;
import com.feisheng.bot.knowledge.service.KnowledgeIndexService;
import com.feisheng.bot.knowledge.service.StructuredKnowledgeUnitIndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeMigrationEndToEndTest {
    @Test
    void zeroConflictJobCannotSwitchUntilAnOperatorConfirmsIt() {
        Fixture fixture = fixture(unit("APPROVED"), List.of());

        KnowledgeDocumentReleaseService.ReleaseException error = assertThrows(
            KnowledgeDocumentReleaseService.ReleaseException.class,
            () -> fixture.release.switchMigration(7L, 9L));

        assertEquals(409, error.status());
        assertEquals("REVIEW_REQUIRED", fixture.job.getStatus());
        assertEquals("PUBLISHED", fixture.source.getPublishStatus());
        assertEquals("DRAFT", fixture.target.getPublishStatus());
    }

    @Test
    void unreviewedUnitsAndUnresolvedConflictsKeepTheReleaseGateClosed() {
        BotKnowledgeConflict conflict = new BotKnowledgeConflict();
        conflict.setId(30L);
        conflict.setMigrationJobId(7L);
        conflict.setSeverity("BLOCKING");
        conflict.setStatus("PENDING");
        Fixture fixture = fixture(unit("DRAFT"), List.of(conflict));

        KnowledgeMigrationReviewService.GateReport report = fixture.review.confirmDocument(
            7L, new KnowledgeMigrationReviewService.ConfirmationRequest("reviewed"), 9L);

        assertFalse(report.passed());
        assertEquals(1, report.unreviewedUnits());
        assertEquals(1, report.blockingConflicts());
        assertEquals("REVIEW_REQUIRED", fixture.job.getStatus());
        assertEquals("PUBLISHED", fixture.source.getPublishStatus());
        assertEquals("DRAFT", fixture.target.getPublishStatus());
    }

    @Test
    void confirmedMigrationSwitchesOnlyTheTargetAndRollbackRestoresTheSource() {
        Fixture fixture = fixture(unit("APPROVED"), List.of());

        KnowledgeMigrationReviewService.GateReport gate = fixture.review.confirmDocument(
            7L, new KnowledgeMigrationReviewService.ConfirmationRequest("approved for release"), 9L);
        KnowledgeDocumentReleaseService.ReleaseResult switched = fixture.release.switchMigration(7L, 9L);

        assertTrue(gate.passed());
        assertEquals("COMPLETED", fixture.job.getStatus());
        assertEquals(2L, switched.documentId());
        assertEquals("ARCHIVED", fixture.source.getPublishStatus());
        assertEquals("PUBLISHED", fixture.target.getPublishStatus());

        KnowledgeDocumentReleaseService.ReleaseException repeatedSwitch = assertThrows(
            KnowledgeDocumentReleaseService.ReleaseException.class,
            () -> fixture.release.switchMigration(7L, 9L));
        assertEquals(409, repeatedSwitch.status());

        KnowledgeDocumentReleaseService.ReleaseResult rolledBack = fixture.release.rollback("set-a", 1L, 9L);

        assertEquals(1L, rolledBack.documentId());
        assertEquals("PUBLISHED", fixture.source.getPublishStatus());
        assertEquals("ARCHIVED", fixture.target.getPublishStatus());
    }

    private Fixture fixture(BotKnowledgeSemanticUnit unit, List<BotKnowledgeConflict> conflicts) {
        BotKnowledgeMigrationJobMapper jobs = mock(BotKnowledgeMigrationJobMapper.class);
        BotKnowledgeDocumentMapper documents = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeChunkMapper chunks = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeConflictMapper conflictMapper = mock(BotKnowledgeConflictMapper.class);
        BotKnowledgeSemanticUnitMapper units = mock(BotKnowledgeSemanticUnitMapper.class);
        KnowledgeIndexService regular = mock(KnowledgeIndexService.class);
        StructuredKnowledgeUnitIndexService structured = mock(StructuredKnowledgeUnitIndexService.class);

        BotKnowledgeDocument source = document(1L, 1, "PUBLISHED");
        BotKnowledgeDocument target = document(2L, 2, "DRAFT");
        BotKnowledgeChunk sourceChunk = chunk();
        BotKnowledgeMigrationJob job = job();
        Map<Long, BotKnowledgeDocument> byId = Map.of(1L, source, 2L, target);

        when(jobs.selectById(7L)).thenReturn(job);
        when(jobs.findByIdForUpdate(7L)).thenReturn(job);
        when(jobs.confirm(any(), anyLong(), any(), any(), any(), any())).thenReturn(1);
        when(jobs.selectOne(any())).thenReturn(job);
        when(documents.selectById(any())).thenAnswer(invocation -> byId.get(invocation.getArgument(0)));
        when(documents.selectForUpdateByKnowledgeSetKey("set-a")).thenReturn(List.of(source, target));
        when(chunks.selectList(any())).thenReturn(List.of(sourceChunk));
        when(units.selectList(any())).thenReturn(List.of(unit));
        when(conflictMapper.selectList(any())).thenReturn(conflicts);
        when(structured.buildShadowIndex(2L)).thenReturn(new StructuredKnowledgeUnitIndexService.ShadowIndexHandle(
            2L, List.of(new StructuredKnowledgeUnitIndexService.ShadowUnit(20L, List.of(1D), "m", "hash", List.of(10L))), true, null));
        when(structured.validateShadowIndex(any())).thenReturn(
            new StructuredKnowledgeUnitIndexService.ShadowValidation(true, 1, 1, List.of()));
        when(regular.buildShadowIndex(2L)).thenReturn(new KnowledgeIndexService.ShadowIndexHandle(
            2L, List.of(new KnowledgeIndexService.ShadowPoint(10L, List.of(1D), "m", "hash")), true, null));
        when(regular.validateShadowIndex(any())).thenReturn(
            new KnowledgeIndexService.ShadowValidation(true, 1, 1, List.of()));
        when(documents.publishDraftWithSupersedesGuarded(any(), any(), any(), any())).thenAnswer(invocation -> {
            target.setPublishStatus("PUBLISHED");
            target.setSupersedesDocumentId(1L);
            return 1;
        });
        when(documents.archivePublishedGuarded(any(), any())).thenAnswer(invocation -> {
            BotKnowledgeDocument archived = byId.get(invocation.getArgument(0));
            archived.setPublishStatus("ARCHIVED");
            archived.setEffectiveTo(invocation.getArgument(1));
            return 1;
        });
        when(documents.restoreArchivedGuardedInSet(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            source.setPublishStatus("PUBLISHED");
            return 1;
        });

        KnowledgeMigrationReviewService review = new KnowledgeMigrationReviewService(jobs, conflictMapper, units,
            documents, chunks, new ObjectMapper());
        KnowledgeDocumentReleaseService release = new KnowledgeDocumentReleaseService(documents, chunks, regular,
            structured, jobs);
        return new Fixture(review, release, job, source, target);
    }

    private static BotKnowledgeMigrationJob job() {
        BotKnowledgeMigrationJob job = new BotKnowledgeMigrationJob();
        job.setId(7L);
        job.setSourceDocumentId(1L);
        job.setTargetDocumentId(2L);
        job.setSourceVersionId(1L);
        job.setTargetVersionId(2L);
        job.setKnowledgeSetKey("set-a");
        job.setStatus("REVIEW_REQUIRED");
        job.setCurrentStep("REVIEW_REQUIRED");
        job.setLockVersion(0L);
        job.setSourceContentHash(EmbeddingMetadataUtil.contentHash("10\0" + "0\0source\1"));
        job.setUpdatedAt(new Date());
        return job;
    }

    private static BotKnowledgeDocument document(Long id, int version, String status) {
        BotKnowledgeDocument document = new BotKnowledgeDocument();
        document.setId(id);
        document.setStatus(2);
        document.setSourceScope("KNOWLEDGE");
        document.setKnowledgeSetKey("set-a");
        document.setDocumentVersion(version);
        document.setPublishStatus(status);
        document.setDeleted(0);
        return document;
    }

    private static BotKnowledgeChunk chunk() {
        BotKnowledgeChunk chunk = new BotKnowledgeChunk();
        chunk.setId(10L);
        chunk.setDocumentId(1L);
        chunk.setChunkIndex(0);
        chunk.setContent("source");
        return chunk;
    }

    private static BotKnowledgeSemanticUnit unit(String status) {
        BotKnowledgeSemanticUnit unit = new BotKnowledgeSemanticUnit();
        unit.setId(20L);
        unit.setDocumentId(2L);
        unit.setStatus(status);
        unit.setEmbedding("[0.1]");
        unit.setEvidenceChunkIdsJson("[10]");
        unit.setSourceSpansJson("[{\"chunkId\":10,\"start\":0,\"end\":6,\"quote\":\"source\"}]");
        unit.setExtractionConfidence(1.0d);
        unit.setDeleted(0);
        return unit;
    }

    private record Fixture(KnowledgeMigrationReviewService review, KnowledgeDocumentReleaseService release,
                           BotKnowledgeMigrationJob job, BotKnowledgeDocument source, BotKnowledgeDocument target) {}
}
