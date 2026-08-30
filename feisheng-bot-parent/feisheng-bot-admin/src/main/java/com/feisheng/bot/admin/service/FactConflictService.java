package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotKnowledgeConflict;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.mapper.BotKnowledgeConflictMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeMigrationJobMapper;
import com.feisheng.bot.admin.entity.BotKnowledgeMigrationJob;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeSemanticUnitMapper;
import com.feisheng.bot.knowledge.service.StructuredKnowledgeUnitIndexService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Candidate recall plus conservative, deterministic fact comparison. */
@Service
public class FactConflictService {
    private static final String PENDING = "PENDING";
    private static final String RESOLVED = "RESOLVED";
    private static final String NOT_CONFLICT = "NOT_CONFLICT";

    private final BotKnowledgeDocumentMapper documentMapper;
    private final BotKnowledgeSemanticUnitMapper unitMapper;
    private final BotKnowledgeConflictMapper conflictMapper;
    private final BotKnowledgeMigrationJobMapper migrationJobMapper;
    private final StructuredKnowledgeUnitIndexService indexService;
    private final FactNormalizationService normalizationService;
    private final FactComparisonService comparisonService;
    private final ObjectMapper objectMapper;
    private final int topK;
    private final double minScore;

    public FactConflictService(BotKnowledgeDocumentMapper documentMapper,
                               BotKnowledgeSemanticUnitMapper unitMapper,
                               BotKnowledgeConflictMapper conflictMapper,
                               StructuredKnowledgeUnitIndexService indexService,
                               FactNormalizationService normalizationService,
                               FactComparisonService comparisonService,
                               ObjectMapper objectMapper,
                               @Value("${knowledge.migration.conflict-top-k:20}") int topK,
                               @Value("${knowledge.migration.conflict-min-score:0.82}") double minScore) {
        this(documentMapper, unitMapper, conflictMapper, null, indexService,
            normalizationService, comparisonService, objectMapper, topK, minScore);
    }

    @Autowired
    public FactConflictService(BotKnowledgeDocumentMapper documentMapper,
                               BotKnowledgeSemanticUnitMapper unitMapper,
                               BotKnowledgeConflictMapper conflictMapper,
                               BotKnowledgeMigrationJobMapper migrationJobMapper,
                               StructuredKnowledgeUnitIndexService indexService,
                               FactNormalizationService normalizationService,
                               FactComparisonService comparisonService,
                               ObjectMapper objectMapper, int topK, double minScore) {
        this.documentMapper = Objects.requireNonNull(documentMapper, "documentMapper");
        this.unitMapper = Objects.requireNonNull(unitMapper, "unitMapper");
        this.conflictMapper = Objects.requireNonNull(conflictMapper, "conflictMapper");
        this.migrationJobMapper = migrationJobMapper;
        this.indexService = Objects.requireNonNull(indexService, "indexService");
        this.normalizationService = Objects.requireNonNull(normalizationService, "normalizationService");
        this.comparisonService = Objects.requireNonNull(comparisonService, "comparisonService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.topK = topK > 0 ? Math.min(topK, 100) : 20;
        this.minScore = Double.isFinite(minScore) ? minScore : 0.82d;
    }

    public ConflictReport check(Long migrationJobId, Long sourceDocumentId,
                                Long targetDocumentId) {
        Objects.requireNonNull(migrationJobId, "migrationJobId");
        Objects.requireNonNull(sourceDocumentId, "sourceDocumentId");
        Objects.requireNonNull(targetDocumentId, "targetDocumentId");
        BotKnowledgeDocument target = documentMapper.selectById(targetDocumentId);
        if (target == null || target.getKnowledgeSetKey() == null
                || target.getKnowledgeSetKey().isBlank()) {
            throw new IllegalArgumentException("target document has no knowledgeSetKey");
        }
        if (migrationJobMapper != null) {
            BotKnowledgeMigrationJob job = migrationJobMapper.selectById(migrationJobId);
            if (job == null || !Objects.equals(sourceDocumentId, job.getSourceDocumentId())
                    || (job.getTargetDocumentId() != null
                        && !Objects.equals(targetDocumentId, job.getTargetDocumentId()))) {
                throw new IllegalArgumentException("migration job is not bound to source/target document");
            }
        }
        List<BotKnowledgeSemanticUnit> targets = unitMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeSemanticUnit>()
                .eq(BotKnowledgeSemanticUnit::getDocumentId, targetDocumentId)
                .ne(BotKnowledgeSemanticUnit::getDeleted, 1));
        int candidatePairs = 0;
        int blocking = 0;
        int warning = 0;
        int info = 0;
        int unknown = 0;
        for (BotKnowledgeSemanticUnit targetUnit : targets) {
            List<Double> vector = parseVector(targetUnit.getEmbedding());
            if (!validVector(vector)) {
                unknown++;
                blocking++;
                persistBlocking(migrationJobId, targetUnit, "VECTOR", "target vector missing or invalid");
                continue;
            }
            FactNormalizationService.NormalizedFact right = normalizationService.normalize(targetUnit);
            if (!validEvidence(targetUnit.getEvidenceChunkIdsJson())
                    || right.scope().relation() == FactNormalizationService.ScopeRelation.UNKNOWN) {
                unknown++;
                blocking++;
                persistBlocking(migrationJobId, targetUnit, "EVIDENCE_OR_SCOPE",
                    !validEvidence(targetUnit.getEvidenceChunkIdsJson())
                        ? "target evidence is malformed" : "target scope is unknown");
                continue;
            }
            List<StructuredKnowledgeUnitIndexService.ConflictCandidate> candidates =
                indexService.searchConflictCandidates(
                    new StructuredKnowledgeUnitIndexService.ConflictQuery(
                        vector, target.getKnowledgeSetKey(), targetDocumentId, topK, minScore,
                        sourceDocumentId, scopeFields(right)));
            for (StructuredKnowledgeUnitIndexService.ConflictCandidate candidate : candidates) {
                BotKnowledgeSemanticUnit sourceUnit = candidate.semanticUnit();
                if (sourceUnit == null || sourceUnit.getId() == null
                        || Objects.equals(sourceUnit.getDocumentId(), targetDocumentId)) continue;
                FactNormalizationService.NormalizedFact left = normalizationService.normalize(sourceUnit);
                FactComparisonService.ComparisonResult result = comparisonService.compare(left, right);
                if (!validEvidence(sourceUnit.getEvidenceChunkIdsJson())) {
                    result = new FactComparisonService.ComparisonResult(
                        FactComparisonService.Relation.UNKNOWN,
                        FactComparisonService.ConflictType.SCOPE,
                        FactComparisonService.Severity.BLOCKING,
                        List.of("evidence"), "source evidence is malformed");
                }
                persist(migrationJobId, targetUnit, sourceUnit, candidate.similarity(), result, left, right);
                candidatePairs++;
                switch (result.severity()) {
                    case BLOCKING -> blocking++;
                    case WARNING -> warning++;
                    case INFO -> info++;
                }
                if (result.relation() == FactComparisonService.Relation.UNKNOWN) unknown++;
            }
        }
        return new ConflictReport(targets.size(), candidatePairs, blocking, warning, info, unknown);
    }

    private Map<String, String> scopeFields(FactNormalizationService.NormalizedFact fact) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String key : List.of("product", "channel", "audience")) {
            String value = fact.scope().fields().get(key);
            if (value != null && !value.isBlank()) fields.put(key, value);
        }
        return fields;
    }

    private boolean validVector(List<Double> vector) {
        return vector != null && !vector.isEmpty() && vector.stream().allMatch(v -> v != null && Double.isFinite(v));
    }

    private boolean validEvidence(String json) {
        if (json == null || json.isBlank()) return false;
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
            if (!node.isArray() || node.isEmpty()) return false;
            for (com.fasterxml.jackson.databind.JsonNode value : node) {
                if (!value.canConvertToLong() || value.asLong() <= 0) return false;
            }
            return true;
        } catch (Exception ignored) { return false; }
    }

    private void persistBlocking(Long migrationJobId, BotKnowledgeSemanticUnit target,
                                 String type, String message) {
        if (target == null || target.getId() == null) return;
        BotKnowledgeConflict existing = conflictMapper.selectOne(
            new LambdaQueryWrapper<BotKnowledgeConflict>()
                .eq(BotKnowledgeConflict::getMigrationJobId, migrationJobId)
                .eq(BotKnowledgeConflict::getTargetUnitId, target.getId())
                .eq(BotKnowledgeConflict::getCandidateUnitId, 0L)
                .last("LIMIT 1"));
        if (existing != null) {
            if (RESOLVED.equals(existing.getStatus()) || NOT_CONFLICT.equals(existing.getStatus())) return;
            existing.setSeverity("BLOCKING");
            existing.setStatus(PENDING);
            existing.setConflictType(type);
            existing.setScopeRelation("UNKNOWN");
            existing.setEvidence(writeJson(Map.of("targetUnitId", target.getId(), "message", message)));
            existing.setRuleResult(writeJson(Map.of("judgment", "UNKNOWN", "reason", message)));
            existing.setLlmResult(writeJson(Map.of("model", "deterministic-fact-comparison", "version", "v1")));
            existing.setUpdatedAt(new Date());
            conflictMapper.updateById(existing);
            return;
        }
        BotKnowledgeConflict conflict = new BotKnowledgeConflict();
        conflict.setMigrationJobId(migrationJobId);
        conflict.setTargetUnitId(target.getId());
        conflict.setCandidateUnitId(0L);
        conflict.setConflictType(type);
        conflict.setSeverity("BLOCKING");
        conflict.setStatus(PENDING);
        conflict.setScopeRelation("UNKNOWN");
        conflict.setEvidence(writeJson(Map.of("targetUnitId", target.getId(), "message", message)));
        conflict.setRuleResult(writeJson(Map.of("judgment", "UNKNOWN", "reason", message)));
        conflict.setLlmResult(writeJson(Map.of("model", "deterministic-fact-comparison", "version", "v1")));
        conflict.setCreateTime(new Date());
        conflict.setUpdatedAt(new Date());
        conflictMapper.insert(conflict);
    }

    private void persist(Long migrationJobId, BotKnowledgeSemanticUnit target,
                         BotKnowledgeSemanticUnit source, double similarity,
                         FactComparisonService.ComparisonResult result,
                         FactNormalizationService.NormalizedFact left,
                         FactNormalizationService.NormalizedFact right) {
        if (source == null || source.getId() == null || target.getId() == null) return;
        BotKnowledgeConflict existing = conflictMapper.selectOne(
            new LambdaQueryWrapper<BotKnowledgeConflict>()
                .eq(BotKnowledgeConflict::getMigrationJobId, migrationJobId)
                .eq(BotKnowledgeConflict::getTargetUnitId, target.getId())
                .eq(BotKnowledgeConflict::getCandidateUnitId, source.getId())
                .last("LIMIT 1"));
        if (existing != null) {
            if (RESOLVED.equals(existing.getStatus()) || NOT_CONFLICT.equals(existing.getStatus())) return;
            existing.setSimilarity(similarity);
            existing.setConflictType(result.conflictType().name());
            existing.setSeverity(result.severity().name());
            existing.setScopeRelation(result.relation().name());
            existing.setEvidence(evidence(target, source, similarity, left, right));
            existing.setRuleResult(writeJson(result));
            existing.setLlmResult(writeJson(Map.of("model", "deterministic-fact-comparison",
                "version", "v1", "judgment", result)));
            existing.setUpdatedAt(new Date());
            conflictMapper.updateById(existing);
            return;
        }
        BotKnowledgeConflict conflict = new BotKnowledgeConflict();
        conflict.setMigrationJobId(migrationJobId);
        conflict.setTargetUnitId(target.getId());
        conflict.setCandidateUnitId(source.getId());
        conflict.setSimilarity(similarity);
        conflict.setScopeRelation(result.relation().name());
        conflict.setConflictType(result.conflictType().name());
        conflict.setSeverity(result.severity().name());
        conflict.setStatus(result.relation() == FactComparisonService.Relation.NOT_CONFLICT
            || result.relation() == FactComparisonService.Relation.SCOPE_DIFFERENCE
            ? NOT_CONFLICT : PENDING);
        conflict.setEvidence(evidence(target, source, similarity, left, right));
        conflict.setRuleResult(writeJson(result));
        conflict.setLlmResult(writeJson(Map.of("model", "deterministic-fact-comparison",
            "version", "v1", "judgment", result)));
        conflict.setCreateTime(new Date());
        conflict.setUpdatedAt(new Date());
        conflictMapper.insert(conflict);
    }

    private String evidence(BotKnowledgeSemanticUnit target, BotKnowledgeSemanticUnit source,
                            double similarity,
                            FactNormalizationService.NormalizedFact left,
                            FactNormalizationService.NormalizedFact right) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceUnitId", source.getId());
        value.put("targetUnitId", target.getId());
        value.put("sourceEvidenceChunkIds", left.evidenceChunkIds());
        value.put("targetEvidenceChunkIds", right.evidenceChunkIds());
        value.put("sourceQuestion", left.originalQuestion());
        value.put("sourceStatement", left.originalStatement());
        value.put("targetQuestion", right.originalQuestion());
        value.put("targetStatement", right.originalStatement());
        value.put("retrieval", Map.of("similarity", similarity, "topK", topK,
            "minScore", minScore,
            "sourceExtractorModel", source.getExtractorModel() == null ? "" : source.getExtractorModel(),
            "targetExtractorModel", target.getExtractorModel() == null ? "" : target.getExtractorModel(),
            "sourceEmbeddingModel", source.getEmbeddingModel() == null ? "" : source.getEmbeddingModel(),
            "targetEmbeddingModel", target.getEmbeddingModel() == null ? "" : target.getEmbeddingModel()));
        return writeJson(value);
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ignored) { return "{}"; }
    }

    private List<Double> parseVector(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Double> values = objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class));
            return validVector(values) ? values : List.of();
        } catch (Exception ignored) { return List.of(); }
    }

    public record ConflictReport(int totalTargetUnits, int candidatePairs,
                                 int blocking, int warning, int info, int unknown) {}
}
