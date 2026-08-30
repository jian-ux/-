package com.feisheng.bot.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.knowledge.entity.BotKnowledgeChunk;
import com.feisheng.bot.knowledge.entity.BotKnowledgeDocument;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeSemanticUnitMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StructuredKnowledgeUnitIndexServiceTest {
    @Test
    void indexesOnlyReviewedGroundedUnitsAsCandidateOnlyResults() {
        BotKnowledgeSemanticUnitMapper unitMapper = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeSemanticUnit approved = unit(1L, "APPROVED", "[1,0]", "[101]");
        approved.setCategoryId(999L);
        BotKnowledgeSemanticUnit draft = unit(2L, "DRAFT", "[1,0]", "[101]");
        when(unitMapper.selectList(any())).thenReturn(List.of(approved, draft));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
            chunk(101L, 7L, "APPROVED", 0),
            chunk(102L, 8L, "APPROVED", 0),
            chunk(999L, 7L, "PENDING", 0)));
        when(documentMapper.selectList(any())).thenReturn(List.of(document(7L, 42L)));
        StructuredKnowledgeUnitIndexService service = new StructuredKnowledgeUnitIndexService(
            unitMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());

        StructuredKnowledgeUnitIndexService.SyncReport report = service.sync();
        List<Map<String, Object>> results = service.search(
            List.of(1.0, 0.0), 1, 0.5, Map.of("categoryId", 42));

        assertTrue(report.success());
        assertEquals(1, report.units());
        assertEquals(1, results.size());
        Map<String, Object> result = results.get(0);
        assertEquals(1L, result.get("semanticUnitId"));
        assertEquals(42L, result.get("categoryId"));
        assertEquals("KNOWLEDGE", result.get("sourceScope"));
        assertEquals("2027-01-02T03:04:05Z", result.get("expiresAt"));
        assertEquals(List.of(101L), result.get("evidenceChunkIds"));
        assertEquals(Map.of("product", "signing"), result.get("candidateMetadata"));
        assertFalse(result.containsKey("metadata"));
        assertEquals(true, result.get("candidateOnly"));
        assertEquals(true, result.get("requiresEvidence"));
        assertFalse(result.containsKey("answer"));
        assertFalse(result.containsKey("fullAnswer"));
        assertFalse(result.containsKey("directAnswerEnabled"));
        assertFalse(result.containsKey("directAnswerEligible"));
        assertTrue(service.search(List.of(1.0, 0.0), 1, -1,
            Map.of("candidateMetadata.product", "signing")).isEmpty());
    }

    @Test
    void appliesFiltersBeforeMemoryTopK() {
        BotKnowledgeSemanticUnitMapper unitMapper = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeSemanticUnit first = unit(1L, "APPROVED", "[1,0]", "[101]");
        BotKnowledgeSemanticUnit second = unit(2L, "APPROVED", "[0.9,0.1]", "[201]");
        second.setDocumentId(8L);
        when(unitMapper.selectList(any())).thenReturn(List.of(first, second));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
            chunk(101L, 7L, "APPROVED", 0), chunk(201L, 8L, "APPROVED", 0)));
        when(documentMapper.selectList(any())).thenReturn(List.of(
            document(7L, 42L), document(8L, 84L)));
        StructuredKnowledgeUnitIndexService service = new StructuredKnowledgeUnitIndexService(
            unitMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());
        service.sync();

        List<Map<String, Object>> result = service.search(
            List.of(1.0, 0.0), 1, -1, Map.of("categoryId", 84));

        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).get("semanticUnitId"));
    }

    @Test
    void excludesUnitsFromDocumentsThatAreStillProcessing() {
        BotKnowledgeSemanticUnitMapper unitMapper = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        when(unitMapper.selectList(any())).thenReturn(List.of(
            unit(1L, "APPROVED", "[1,0]", "[101]")));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
            chunk(101L, 7L, "APPROVED", 0)));
        BotKnowledgeDocument processing = document(7L, 42L);
        processing.setStatus(1);
        when(documentMapper.selectList(any())).thenReturn(List.of(processing));
        StructuredKnowledgeUnitIndexService service = new StructuredKnowledgeUnitIndexService(
            unitMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());

        StructuredKnowledgeUnitIndexService.SyncReport report = service.sync();

        assertEquals(0, report.units());
        assertTrue(service.search(List.of(1.0, 0.0), 3, -1, Map.of()).isEmpty());
    }

    @Test
    void excludesUnitWhenAnyDeclaredEvidenceIsNotApprovedInTheSameDocument() {
        BotKnowledgeSemanticUnitMapper unitMapper = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        when(unitMapper.selectList(any())).thenReturn(List.of(
            unit(1L, "APPROVED", "[1,0]", "[101,102]")));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
            chunk(101L, 7L, "APPROVED", 0),
            chunk(102L, 7L, "PENDING", 0)));
        when(documentMapper.selectList(any())).thenReturn(List.of(document(7L, 42L)));
        StructuredKnowledgeUnitIndexService service = new StructuredKnowledgeUnitIndexService(
            unitMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());

        StructuredKnowledgeUnitIndexService.SyncReport report = service.sync();

        assertEquals(0, report.units());
        assertTrue(service.search(List.of(1.0, 0.0), 3, -1, Map.of()).isEmpty());
    }

    @Test
    void clearsSupplementalCandidatesWhenAuthoritativeReloadFails() {
        BotKnowledgeSemanticUnitMapper unitMapper = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        when(unitMapper.selectList(any()))
            .thenReturn(List.of(unit(1L, "APPROVED", "[1,0]", "[101]")))
            .thenThrow(new IllegalStateException("database unavailable"));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
            chunk(101L, 7L, "APPROVED", 0)));
        when(documentMapper.selectList(any())).thenReturn(List.of(document(7L, 42L)));
        StructuredKnowledgeUnitIndexService service = new StructuredKnowledgeUnitIndexService(
            unitMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());
        assertTrue(service.sync().success());
        assertEquals(1, service.status().units());

        StructuredKnowledgeUnitIndexService.SyncReport failed = service.sync();

        assertFalse(failed.success());
        assertEquals(0, service.status().units());
        assertTrue(service.search(List.of(1.0, 0.0), 3, -1, Map.of()).isEmpty());
    }

    @Test
    void disabledGateNeverTouchesDatabaseObjectMapperOrQdrant() {
        BotKnowledgeSemanticUnitMapper unitMapper = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        QdrantVectorStore qdrant = mock(QdrantVectorStore.class);
        StructuredKnowledgeUnitIndexService service = new StructuredKnowledgeUnitIndexService(
            unitMapper, chunkMapper, documentMapper, objectMapper, qdrant,
            false, "semantic-units");

        service.initialize();
        service.scheduledSync();
        StructuredKnowledgeUnitIndexService.SyncReport report = service.sync();
        List<Map<String, Object>> results = service.search(
            List.of(1.0, 0.0), 5, -1, Map.of("categoryId", 42));
        StructuredKnowledgeUnitIndexService.IndexStatus status = service.status();

        assertFalse(report.success());
        assertEquals("disabled", report.error());
        assertTrue(results.isEmpty());
        assertEquals("disabled", status.searchBackend());
        assertEquals(0, status.units());
        assertFalse(status.qdrantReady());
        assertFalse(status.qdrant().enabled());
        assertEquals("disabled", status.qdrant().error());
        assertEquals("disabled", status.lastSync().error());
        verifyNoInteractions(unitMapper, chunkMapper, documentMapper, objectMapper, qdrant);
    }

    @Test
    void qdrantHitsUseAuthoritativeApprovedSnapshotPayload() {
        BotKnowledgeSemanticUnitMapper unitMapper = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        QdrantVectorStore qdrant = mock(QdrantVectorStore.class);
        when(unitMapper.selectList(any())).thenReturn(List.of(
            unit(1L, "APPROVED", "[1,0]", "[101]")));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
            chunk(101L, 7L, "APPROVED", 0)));
        when(documentMapper.selectList(any())).thenReturn(List.of(document(7L, 42L)));
        when(qdrant.isEnabled()).thenReturn(true);
        when(qdrant.reconcile(any())).thenReturn(
            new QdrantVectorStore.ReconcileResult(1, 0));
        when(qdrant.search(any(), anyInt(), anyDouble(), any())).thenReturn(List.of(
            new QdrantVectorStore.SearchHit(Map.of(
                "semanticUnitId", 999L, "sourceId", 999L,
                "evidenceChunkIds", List.of(999L)), 0.99),
            new QdrantVectorStore.SearchHit(Map.of(
                "semanticUnitId", 1L, "sourceId", 1L,
                "categoryId", 999L, "evidenceChunkIds", List.of(999L),
                "metadata", Map.of("risk_level", "LOW")), 0.91)));
        StructuredKnowledgeUnitIndexService service = new StructuredKnowledgeUnitIndexService(
            unitMapper, chunkMapper, documentMapper, new ObjectMapper(), qdrant);
        assertTrue(service.sync().success());

        List<Map<String, Object>> results = service.search(
            List.of(1.0, 0.0), 5, -1, Map.of("categoryId", 42));

        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).get("semanticUnitId"));
        assertEquals(42L, results.get(0).get("categoryId"));
        assertEquals(List.of(101L), results.get(0).get("evidenceChunkIds"));
        assertEquals(Map.of("product", "signing"),
            results.get(0).get("candidateMetadata"));
        assertFalse(results.get(0).containsKey("metadata"));
    }

    @Test
    void rejectedUnitCannotLeakWhileQdrantDeletionIsInProgress() throws Exception {
        BotKnowledgeSemanticUnitMapper unitMapper = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        QdrantVectorStore qdrant = mock(QdrantVectorStore.class);
        BotKnowledgeSemanticUnit approved = unit(1L, "APPROVED", "[1,0]", "[101]");
        when(unitMapper.selectList(any())).thenReturn(List.of(approved), List.of());
        when(chunkMapper.selectList(any())).thenReturn(List.of(
            chunk(101L, 7L, "APPROVED", 0)));
        when(documentMapper.selectList(any())).thenReturn(List.of(document(7L, 42L)));
        when(qdrant.isEnabled()).thenReturn(true);
        when(qdrant.reconcile(any())).thenReturn(
            new QdrantVectorStore.ReconcileResult(1, 0));
        when(qdrant.search(any(), anyInt(), anyDouble())).thenReturn(List.of(
            new QdrantVectorStore.SearchHit(Map.of(
                "semanticUnitId", 1L, "sourceId", 1L,
                "evidenceChunkIds", List.of(101L)), 0.95)));
        CountDownLatch deletionStarted = new CountDownLatch(1);
        CountDownLatch allowDeletion = new CountDownLatch(1);
        when(qdrant.applyChanges(any(), any(), any())).thenAnswer(invocation -> {
            deletionStarted.countDown();
            if (!allowDeletion.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to complete deletion");
            }
            return new QdrantVectorStore.ReconcileResult(0, 1);
        });
        StructuredKnowledgeUnitIndexService service = new StructuredKnowledgeUnitIndexService(
            unitMapper, chunkMapper, documentMapper, new ObjectMapper(), qdrant);
        assertTrue(service.sync().success());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<StructuredKnowledgeUnitIndexService.SyncReport> rejectSync =
            executor.submit(service::sync);
        try {
            assertTrue(deletionStarted.await(5, TimeUnit.SECONDS));
            assertTrue(service.search(List.of(1.0, 0.0), 5, -1, Map.of()).isEmpty());
        } finally {
            allowDeletion.countDown();
            executor.shutdown();
        }
        assertTrue(rejectSync.get(5, TimeUnit.SECONDS).success());
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void conflictRecallUsesOnlyPublishedUnitsInTheRequestedKnowledgeSet() {
        BotKnowledgeSemanticUnitMapper unitMapper = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeSemanticUnit source = unit(1L, "APPROVED", "[1,0]", "[101]");
        BotKnowledgeSemanticUnit otherSet = unit(2L, "APPROVED", "[1,0]", "[201]");
        otherSet.setDocumentId(8L);
        when(unitMapper.selectList(any())).thenReturn(List.of(source, otherSet));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
            chunk(101L, 7L, "APPROVED", 0), chunk(201L, 8L, "APPROVED", 0)));
        BotKnowledgeDocument published = document(7L, 42L);
        BotKnowledgeDocument draft = document(8L, 84L);
        draft.setKnowledgeSetKey("other-set");
        draft.setPublishStatus("DRAFT");
        when(documentMapper.selectList(any())).thenReturn(List.of(published, draft));
        StructuredKnowledgeUnitIndexService service = new StructuredKnowledgeUnitIndexService(
            unitMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());
        assertTrue(service.sync().success());

        List<StructuredKnowledgeUnitIndexService.ConflictCandidate> candidates =
            service.searchConflictCandidates(new StructuredKnowledgeUnitIndexService.ConflictQuery(
                List.of(1.0, 0.0), "set-a", 999L, 20, 0.5));

        assertEquals(1, candidates.size());
        assertEquals(1L, candidates.get(0).semanticUnit().getId());
        assertEquals("set-a", candidates.get(0).knowledgeSetKey());
    }

    @Test
    void conflictRecallAppliesAuthoritativeSourceAndAudienceScope() {
        BotKnowledgeSemanticUnitMapper unitMapper = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeSemanticUnit source = unit(1L, "APPROVED", "[1,0]", "[101]");
        source.setDocumentId(7L);
        source.setMetadataJson("{\"product\":\"signing\",\"channel\":\"web\",\"audience\":\"enterprise\"}");
        BotKnowledgeSemanticUnit wrongSource = unit(2L, "APPROVED", "[1,0]", "[201]");
        wrongSource.setDocumentId(8L);
        wrongSource.setMetadataJson("{\"product\":\"signing\",\"channel\":\"web\",\"audience\":\"enterprise\"}");
        when(unitMapper.selectList(any())).thenReturn(List.of(source, wrongSource));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
            chunk(101L, 7L, "APPROVED", 0), chunk(201L, 8L, "APPROVED", 0)));
        BotKnowledgeDocument first = document(7L, 42L);
        BotKnowledgeDocument second = document(8L, 42L);
        when(documentMapper.selectList(any())).thenReturn(List.of(first, second));
        StructuredKnowledgeUnitIndexService service = new StructuredKnowledgeUnitIndexService(
            unitMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());
        service.sync();
        List<StructuredKnowledgeUnitIndexService.ConflictCandidate> candidates =
            service.searchConflictCandidates(new StructuredKnowledgeUnitIndexService.ConflictQuery(
                List.of(1.0, 0.0), "set-a", 999L, 20, 0.5, 7L,
                Map.of("product", "signing", "channel", "web", "audience", "enterprise")));
        assertEquals(1, candidates.size());
        assertEquals(1L, candidates.get(0).semanticUnit().getId());
    }

    @Test
    void conflictRecallFiltersScopeBeforeApplyingTopK() {
        BotKnowledgeSemanticUnitMapper unitMapper = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeSemanticUnit wrongScope = unit(1L, "APPROVED", "[1,0]", "[101]");
        wrongScope.setMetadataJson("{\"product\":\"signing\",\"audience\":\"consumer\"}");
        BotKnowledgeSemanticUnit matchingScope = unit(2L, "APPROVED", "[0.9,0.1]", "[102]");
        matchingScope.setMetadataJson("{\"product\":\"signing\",\"audience\":\"enterprise\"}");
        when(unitMapper.selectList(any())).thenReturn(List.of(wrongScope, matchingScope));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
            chunk(101L, 7L, "APPROVED", 0), chunk(102L, 7L, "APPROVED", 0)));
        when(documentMapper.selectList(any())).thenReturn(List.of(document(7L, 42L)));
        StructuredKnowledgeUnitIndexService service = new StructuredKnowledgeUnitIndexService(
            unitMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());
        service.sync();

        List<StructuredKnowledgeUnitIndexService.ConflictCandidate> candidates =
            service.searchConflictCandidates(new StructuredKnowledgeUnitIndexService.ConflictQuery(
                List.of(1.0, 0.0), "set-a", 999L, 1, 0.5, 7L,
                Map.of("product", "signing", "audience", "enterprise")));

        assertEquals(1, candidates.size());
        assertEquals(2L, candidates.get(0).semanticUnit().getId());
    }

    private BotKnowledgeSemanticUnit unit(Long id, String status, String embedding,
                                          String evidenceChunkIds) {
        BotKnowledgeSemanticUnit unit = new BotKnowledgeSemanticUnit();
        unit.setId(id);
        unit.setDocumentId(7L);
        unit.setUnitKey("unit-" + id);
        unit.setUnitType("FACT");
        unit.setQuestion("How does signing work?");
        unit.setStatement("Signing requires verified identity.");
        unit.setIntent("sign_contract");
        unit.setEvidenceChunkIdsJson(evidenceChunkIds);
        unit.setMetadataJson("{\"product\":\"signing\"}");
        unit.setEmbedding(embedding);
        unit.setStatus(status);
        unit.setDeleted(0);
        return unit;
    }

    private BotKnowledgeChunk chunk(Long id, Long documentId, String status, int deleted) {
        BotKnowledgeChunk chunk = new BotKnowledgeChunk();
        chunk.setId(id);
        chunk.setDocumentId(documentId);
        chunk.setStatus(status);
        chunk.setDeleted(deleted);
        return chunk;
    }

    private BotKnowledgeDocument document(Long id, Long categoryId) {
        BotKnowledgeDocument document = new BotKnowledgeDocument();
        document.setId(id);
        document.setTitle("Signing manual");
        document.setCategoryId(categoryId);
        document.setSourceScope("KNOWLEDGE");
        document.setPublishStatus("PUBLISHED");
        document.setKnowledgeSetKey("set-a");
        document.setExpiresAt(Date.from(Instant.parse("2027-01-02T03:04:05Z")));
        document.setStatus(2);
        document.setDeleted(0);
        return document;
    }

    private QdrantVectorStore disabledQdrant() {
        return new QdrantVectorStore(
            new RestTemplate(), false, "http://qdrant:6333",
            "semantic-units", 2, 64);
    }
}
