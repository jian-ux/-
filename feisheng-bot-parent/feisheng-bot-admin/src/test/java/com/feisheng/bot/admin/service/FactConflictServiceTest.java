package com.feisheng.bot.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.mapper.BotKnowledgeConflictMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeMigrationJobMapper;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeSemanticUnitMapper;
import com.feisheng.bot.knowledge.service.StructuredKnowledgeUnitIndexService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FactConflictServiceTest {
    @Test
    void recallsCandidateComparesFactAndPersistsOnePair() {
        BotKnowledgeDocumentMapper documents = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeSemanticUnitMapper units = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeConflictMapper conflicts = mock(BotKnowledgeConflictMapper.class);
        StructuredKnowledgeUnitIndexService index = mock(StructuredKnowledgeUnitIndexService.class);
        BotKnowledgeDocument targetDocument = new BotKnowledgeDocument();
        targetDocument.setId(20L);
        targetDocument.setKnowledgeSetKey("set-a");
        when(documents.selectById(20L)).thenReturn(targetDocument);

        BotKnowledgeSemanticUnit target = unit(200L, 20L, "不可以办理");
        BotKnowledgeSemanticUnit source = unit(100L, 10L, "可以办理");
        when(units.selectList(any())).thenReturn(List.of(target));
        when(index.searchConflictCandidates(any())).thenReturn(List.of(
            new StructuredKnowledgeUnitIndexService.ConflictCandidate(source, 0.94d, 10L, "set-a")));
        when(conflicts.selectOne(any())).thenReturn(null);

        FactConflictService service = new FactConflictService(documents, units, conflicts, index,
            new FactNormalizationService(new ObjectMapper()), new FactComparisonService(),
            new ObjectMapper(), 20, 0.82d);
        FactConflictService.ConflictReport report = service.check(1L, 10L, 20L);

        assertEquals(1, report.totalTargetUnits());
        assertEquals(1, report.candidatePairs());
        assertEquals(1, report.blocking());
        verify(conflicts).insert(any(com.feisheng.bot.admin.entity.BotKnowledgeConflict.class));
    }

    @Test
    void springConstructorInjectsConflictRecallConfiguration() throws Exception {
        Constructor<?> constructor = java.util.Arrays.stream(FactConflictService.class.getDeclaredConstructors())
            .filter(candidate -> candidate.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class))
            .findFirst().orElseThrow();

        Value topK = constructor.getParameters()[8].getAnnotation(Value.class);
        Value minScore = constructor.getParameters()[9].getAnnotation(Value.class);

        org.junit.jupiter.api.Assertions.assertNotNull(topK);
        org.junit.jupiter.api.Assertions.assertNotNull(minScore);
        assertEquals("${knowledge.migration.conflict-top-k:20}", topK.value());
        assertEquals("${knowledge.migration.conflict-min-score:0.82}", minScore.value());
    }

    @Test
    void missingTargetVectorIsBlockingUnknownWithReviewRow() {
        BotKnowledgeDocumentMapper documents = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeSemanticUnitMapper units = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeConflictMapper conflicts = mock(BotKnowledgeConflictMapper.class);
        StructuredKnowledgeUnitIndexService index = mock(StructuredKnowledgeUnitIndexService.class);
        BotKnowledgeDocument targetDocument = new BotKnowledgeDocument();
        targetDocument.setKnowledgeSetKey("set-a");
        when(documents.selectById(20L)).thenReturn(targetDocument);
        BotKnowledgeSemanticUnit missingVector = unit(200L, 20L, "可以办理");
        missingVector.setEmbedding(null);
        when(units.selectList(any())).thenReturn(List.of(missingVector));
        FactConflictService service = new FactConflictService(documents, units, conflicts, index,
            new FactNormalizationService(new ObjectMapper()), new FactComparisonService(),
            new ObjectMapper(), 20, 0.82d);

        FactConflictService.ConflictReport report = service.check(1L, 10L, 20L);

        assertEquals(1, report.unknown());
        assertEquals(0, report.candidatePairs());
        verify(conflicts).insert(any(com.feisheng.bot.admin.entity.BotKnowledgeConflict.class));
        verifyNoInteractions(index);
    }

    @Test
    void rejectsJobBoundToDifferentSourceDocument() {
        BotKnowledgeDocumentMapper documents = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeSemanticUnitMapper units = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeConflictMapper conflicts = mock(BotKnowledgeConflictMapper.class);
        BotKnowledgeMigrationJobMapper jobs = mock(BotKnowledgeMigrationJobMapper.class);
        StructuredKnowledgeUnitIndexService index = mock(StructuredKnowledgeUnitIndexService.class);
        BotKnowledgeDocument target = new BotKnowledgeDocument();
        target.setKnowledgeSetKey("set-a");
        when(documents.selectById(20L)).thenReturn(target);
        com.feisheng.bot.admin.entity.BotKnowledgeMigrationJob job =
            new com.feisheng.bot.admin.entity.BotKnowledgeMigrationJob();
        job.setSourceDocumentId(11L);
        when(jobs.selectById(1L)).thenReturn(job);
        FactConflictService service = new FactConflictService(documents, units, conflicts, jobs,
            index, new FactNormalizationService(new ObjectMapper()), new FactComparisonService(),
            new ObjectMapper(), 20, 0.82d);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.check(1L, 10L, 20L));
        verifyNoInteractions(units, conflicts, index);
    }

    @Test
    void reDetectionResetsReviewedPairWhenSourceSnapshotChanges() {
        BotKnowledgeDocumentMapper documents = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeSemanticUnitMapper units = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeConflictMapper conflicts = mock(BotKnowledgeConflictMapper.class);
        StructuredKnowledgeUnitIndexService index = mock(StructuredKnowledgeUnitIndexService.class);
        BotKnowledgeDocument targetDocument = new BotKnowledgeDocument();
        targetDocument.setId(20L);
        targetDocument.setKnowledgeSetKey("set-a");
        when(documents.selectById(20L)).thenReturn(targetDocument);
        BotKnowledgeSemanticUnit target = unit(200L, 20L, "不可以办理");
        target.setSourceHash("target-new");
        BotKnowledgeSemanticUnit source = unit(100L, 10L, "可以办理");
        source.setSourceHash("source-new");
        when(units.selectList(any())).thenReturn(List.of(target));
        when(index.searchConflictCandidates(any())).thenReturn(List.of(
            new StructuredKnowledgeUnitIndexService.ConflictCandidate(source, 0.94d, 10L, "set-a")));
        com.feisheng.bot.admin.entity.BotKnowledgeConflict existing = new com.feisheng.bot.admin.entity.BotKnowledgeConflict();
        existing.setId(9L);
        existing.setMigrationJobId(1L);
        existing.setTargetUnitId(200L);
        existing.setCandidateUnitId(100L);
        existing.setStatus("RESOLVED");
        existing.setResolution("MERGE");
        existing.setEvidence("{\"sourceSnapshotHash\":\"source-old\",\"targetSnapshotHash\":\"target-new\"}");
        when(conflicts.selectOne(any())).thenReturn(existing);

        FactConflictService service = new FactConflictService(documents, units, conflicts, index,
            new FactNormalizationService(new ObjectMapper()), new FactComparisonService(),
            new ObjectMapper(), 20, 0.82d);
        service.check(1L, 10L, 20L);

        assertEquals("PENDING", existing.getStatus());
        verify(conflicts).updateById(existing);
    }

    @Test
    void reDetectionPreservesReviewedPairForIdenticalJudgmentInputs() throws Exception {
        BotKnowledgeDocumentMapper documents = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeSemanticUnitMapper units = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeConflictMapper conflicts = mock(BotKnowledgeConflictMapper.class);
        StructuredKnowledgeUnitIndexService index = mock(StructuredKnowledgeUnitIndexService.class);
        BotKnowledgeDocument targetDocument = new BotKnowledgeDocument();
        targetDocument.setId(20L);
        targetDocument.setKnowledgeSetKey("set-a");
        when(documents.selectById(20L)).thenReturn(targetDocument);
        BotKnowledgeSemanticUnit target = unit(200L, 20L, "不可以办理");
        target.setSourceHash("target-same");
        BotKnowledgeSemanticUnit source = unit(100L, 10L, "可以办理");
        source.setSourceHash("source-same");
        when(units.selectList(any())).thenReturn(List.of(target));
        when(index.searchConflictCandidates(any())).thenReturn(List.of(
            new StructuredKnowledgeUnitIndexService.ConflictCandidate(source, 0.94d, 10L, "set-a")));
        com.feisheng.bot.admin.entity.BotKnowledgeConflict existing = new com.feisheng.bot.admin.entity.BotKnowledgeConflict();
        existing.setId(9L);
        existing.setMigrationJobId(1L);
        existing.setTargetUnitId(200L);
        existing.setCandidateUnitId(100L);
        existing.setStatus("RESOLVED");
        existing.setResolution("MERGE");
        when(conflicts.selectOne(any())).thenReturn(null);
        FactConflictService service = new FactConflictService(documents, units, conflicts, index,
            new FactNormalizationService(new ObjectMapper()), new FactComparisonService(),
            new ObjectMapper(), 20, 0.82d);
        service.check(1L, 10L, 20L);
        org.mockito.ArgumentCaptor<com.feisheng.bot.admin.entity.BotKnowledgeConflict> captor =
            org.mockito.ArgumentCaptor.forClass(com.feisheng.bot.admin.entity.BotKnowledgeConflict.class);
        verify(conflicts).insert(captor.capture());
        existing.setEvidence(captor.getValue().getEvidence());
        reset(conflicts);
        when(conflicts.selectOne(any())).thenReturn(existing);
        service.check(1L, 10L, 20L);

        assertEquals("RESOLVED", existing.getStatus());
        verify(conflicts, never()).updateById(any(com.feisheng.bot.admin.entity.BotKnowledgeConflict.class));
    }

    @Test
    void reDetectionResetsReviewedPairWhenJudgmentInputChanges() throws Exception {
        BotKnowledgeDocumentMapper documents = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeSemanticUnitMapper units = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeConflictMapper conflicts = mock(BotKnowledgeConflictMapper.class);
        StructuredKnowledgeUnitIndexService index = mock(StructuredKnowledgeUnitIndexService.class);
        BotKnowledgeDocument targetDocument = new BotKnowledgeDocument();
        targetDocument.setId(20L);
        targetDocument.setKnowledgeSetKey("set-a");
        when(documents.selectById(20L)).thenReturn(targetDocument);
        BotKnowledgeSemanticUnit target = unit(200L, 20L, "不可以办理");
        target.setSourceHash("target-same");
        BotKnowledgeSemanticUnit source = unit(100L, 10L, "可以办理");
        source.setSourceHash("source-same");
        when(units.selectList(any())).thenReturn(List.of(target));
        when(index.searchConflictCandidates(any())).thenReturn(List.of(
            new StructuredKnowledgeUnitIndexService.ConflictCandidate(source, 0.94d, 10L, "set-a")));
        when(conflicts.selectOne(any())).thenReturn(null);
        FactConflictService service = new FactConflictService(documents, units, conflicts, index,
            new FactNormalizationService(new ObjectMapper()), new FactComparisonService(),
            new ObjectMapper(), 20, 0.82d);
        service.check(1L, 10L, 20L);
        org.mockito.ArgumentCaptor<com.feisheng.bot.admin.entity.BotKnowledgeConflict> captor =
            org.mockito.ArgumentCaptor.forClass(com.feisheng.bot.admin.entity.BotKnowledgeConflict.class);
        verify(conflicts).insert(captor.capture());
        com.feisheng.bot.admin.entity.BotKnowledgeConflict existing = captor.getValue();
        existing.setId(9L);
        existing.setStatus("RESOLVED");
        existing.setResolution("MERGE");
        target.setMetadataJson("{\"product\":\"其他合同\"}");
        reset(conflicts);
        when(conflicts.selectOne(any())).thenReturn(existing);

        service.check(1L, 10L, 20L);

        assertEquals("PENDING", existing.getStatus());
        verify(conflicts).updateById(existing);
    }

    @Test
    void reDetectionResetsReviewedPairWhenEmbeddingModelChanges() throws Exception {
        BotKnowledgeDocumentMapper documents = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeSemanticUnitMapper units = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeConflictMapper conflicts = mock(BotKnowledgeConflictMapper.class);
        StructuredKnowledgeUnitIndexService index = mock(StructuredKnowledgeUnitIndexService.class);
        BotKnowledgeDocument targetDocument = new BotKnowledgeDocument();
        targetDocument.setId(20L);
        targetDocument.setKnowledgeSetKey("set-a");
        when(documents.selectById(20L)).thenReturn(targetDocument);
        BotKnowledgeSemanticUnit target = unit(200L, 20L, "不可以办理");
        target.setSourceHash("target-same");
        target.setEmbeddingModel("model-v2");
        BotKnowledgeSemanticUnit source = unit(100L, 10L, "可以办理");
        source.setSourceHash("source-same");
        source.setEmbeddingModel("model-v1");
        when(units.selectList(any())).thenReturn(List.of(target));
        when(index.searchConflictCandidates(any())).thenReturn(List.of(
            new StructuredKnowledgeUnitIndexService.ConflictCandidate(source, 0.94d, 10L, "set-a")));
        when(conflicts.selectOne(any())).thenReturn(null);
        FactConflictService service = new FactConflictService(documents, units, conflicts, index,
            new FactNormalizationService(new ObjectMapper()), new FactComparisonService(),
            new ObjectMapper(), 20, 0.82d);
        service.check(1L, 10L, 20L);
        org.mockito.ArgumentCaptor<com.feisheng.bot.admin.entity.BotKnowledgeConflict> captor =
            org.mockito.ArgumentCaptor.forClass(com.feisheng.bot.admin.entity.BotKnowledgeConflict.class);
        verify(conflicts).insert(captor.capture());
        com.feisheng.bot.admin.entity.BotKnowledgeConflict existing = captor.getValue();
        existing.setId(9L);
        existing.setStatus("RESOLVED");
        existing.setResolution("MERGE");
        target.setEmbeddingModel("model-v3");
        reset(conflicts);
        when(conflicts.selectOne(any())).thenReturn(existing);

        service.check(1L, 10L, 20L);

        assertEquals("PENDING", existing.getStatus());
        verify(conflicts).updateById(existing);
    }

    @Test
    void reDetectionPreservesReviewedPairWhenOnlyJsonObjectFieldOrderChanges() throws Exception {
        BotKnowledgeDocumentMapper documents = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeSemanticUnitMapper units = mock(BotKnowledgeSemanticUnitMapper.class);
        BotKnowledgeConflictMapper conflicts = mock(BotKnowledgeConflictMapper.class);
        StructuredKnowledgeUnitIndexService index = mock(StructuredKnowledgeUnitIndexService.class);
        BotKnowledgeDocument targetDocument = new BotKnowledgeDocument();
        targetDocument.setId(20L);
        targetDocument.setKnowledgeSetKey("set-a");
        when(documents.selectById(20L)).thenReturn(targetDocument);
        BotKnowledgeSemanticUnit target = unit(200L, 20L, "不可以办理");
        target.setSourceHash("target-same");
        target.setMetadataJson("{\"product\":\"合同\",\"region\":\"全国\"}");
        BotKnowledgeSemanticUnit source = unit(100L, 10L, "可以办理");
        source.setSourceHash("source-same");
        source.setMetadataJson("{\"region\":\"全国\",\"product\":\"合同\"}");
        when(units.selectList(any())).thenReturn(List.of(target));
        when(index.searchConflictCandidates(any())).thenReturn(List.of(
            new StructuredKnowledgeUnitIndexService.ConflictCandidate(source, 0.94d, 10L, "set-a")));
        when(conflicts.selectOne(any())).thenReturn(null);
        FactConflictService service = new FactConflictService(documents, units, conflicts, index,
            new FactNormalizationService(new ObjectMapper()), new FactComparisonService(),
            new ObjectMapper(), 20, 0.82d);
        service.check(1L, 10L, 20L);
        org.mockito.ArgumentCaptor<com.feisheng.bot.admin.entity.BotKnowledgeConflict> captor =
            org.mockito.ArgumentCaptor.forClass(com.feisheng.bot.admin.entity.BotKnowledgeConflict.class);
        verify(conflicts).insert(captor.capture());
        com.feisheng.bot.admin.entity.BotKnowledgeConflict existing = captor.getValue();
        existing.setId(9L);
        existing.setStatus("RESOLVED");
        existing.setResolution("MERGE");
        target.setMetadataJson("{\"region\":\"全国\",\"product\":\"合同\"}");
        reset(conflicts);
        when(conflicts.selectOne(any())).thenReturn(existing);

        service.check(1L, 10L, 20L);

        assertEquals("RESOLVED", existing.getStatus());
        verify(conflicts, never()).updateById(any(com.feisheng.bot.admin.entity.BotKnowledgeConflict.class));
    }

    private BotKnowledgeSemanticUnit unit(Long id, Long documentId, String statement) {
        BotKnowledgeSemanticUnit unit = new BotKnowledgeSemanticUnit();
        unit.setId(id);
        unit.setDocumentId(documentId);
        unit.setQuestion("办理方式");
        unit.setStatement(statement);
        unit.setMetadataJson("{\"product\":\"合同\"}");
        unit.setEvidenceChunkIdsJson("[1]");
        unit.setEmbedding("[1.0,0.0]");
        return unit;
    }
}
