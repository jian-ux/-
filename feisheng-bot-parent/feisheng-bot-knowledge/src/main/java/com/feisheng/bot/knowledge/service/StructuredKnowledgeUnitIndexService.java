package com.feisheng.bot.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.knowledge.entity.BotKnowledgeChunk;
import com.feisheng.bot.knowledge.entity.BotKnowledgeDocument;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeSemanticUnitMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Independent candidate-only vector index for reviewed semantic knowledge units. */
@Service
public class StructuredKnowledgeUnitIndexService {
    private static final String DISABLED = "disabled";
    private static final Logger log = LoggerFactory.getLogger(
        StructuredKnowledgeUnitIndexService.class);
    private static final Set<String> PROHIBITED_RESULT_FIELDS = Set.of(
        "answer", "fullAnswer", "qaAnswer", "directAnswerEnabled", "directAnswerEligible");
    private static final Set<String> TRUSTED_FILTER_FIELDS = Set.of(
        "semanticUnitId", "documentId", "categoryId", "sourceScope", "expiresAt");

    private final BotKnowledgeSemanticUnitMapper unitMapper;
    private final BotKnowledgeChunkMapper chunkMapper;
    private final BotKnowledgeDocumentMapper documentMapper;
    private final ObjectMapper objectMapper;
    private final QdrantVectorStore qdrantStore;
    private final boolean enabled;

    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile SyncReport lastReport = SyncReport.empty();
    private volatile boolean qdrantReady;

    @Autowired
    public StructuredKnowledgeUnitIndexService(
            BotKnowledgeSemanticUnitMapper unitMapper,
            BotKnowledgeChunkMapper chunkMapper,
            BotKnowledgeDocumentMapper documentMapper,
            ObjectMapper objectMapper,
            QdrantVectorStore primaryQdrantStore,
            @Value("${rag.structured-unit-index.enabled:${RAG_STRUCTURED_UNIT_INDEX_ENABLED:false}}")
            boolean enabled,
            @Value("${qdrant.semantic-unit-collection:${QDRANT_SEMANTIC_UNIT_COLLECTION:feisheng_knowledge_semantic_units}}")
            String collection) {
        this(unitMapper, chunkMapper, documentMapper, objectMapper,
            enabled ? primaryQdrantStore.forCollection(collection) : null, enabled);
    }

    /** Preserves the original programmatic constructor with the index enabled. */
    public StructuredKnowledgeUnitIndexService(
            BotKnowledgeSemanticUnitMapper unitMapper,
            BotKnowledgeChunkMapper chunkMapper,
            BotKnowledgeDocumentMapper documentMapper,
            ObjectMapper objectMapper,
            QdrantVectorStore primaryQdrantStore,
            String collection) {
        this(unitMapper, chunkMapper, documentMapper, objectMapper,
            primaryQdrantStore.forCollection(collection), true);
    }

    StructuredKnowledgeUnitIndexService(BotKnowledgeSemanticUnitMapper unitMapper,
                                        BotKnowledgeChunkMapper chunkMapper,
                                        BotKnowledgeDocumentMapper documentMapper,
                                        ObjectMapper objectMapper,
                                        QdrantVectorStore qdrantStore) {
        this(unitMapper, chunkMapper, documentMapper, objectMapper, qdrantStore, true);
    }

    StructuredKnowledgeUnitIndexService(BotKnowledgeSemanticUnitMapper unitMapper,
                                        BotKnowledgeChunkMapper chunkMapper,
                                        BotKnowledgeDocumentMapper documentMapper,
                                        ObjectMapper objectMapper,
                                        QdrantVectorStore qdrantStore,
                                        boolean enabled) {
        this.unitMapper = unitMapper;
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.objectMapper = objectMapper;
        this.qdrantStore = qdrantStore;
        this.enabled = enabled;
        if (!enabled) lastReport = SyncReport.disabled();
    }

    @PostConstruct
    public void initialize() {
        sync();
    }

    @Scheduled(fixedDelayString = "${rag.index.sync-interval-ms:30000}")
    public void scheduledSync() {
        sync();
    }

    public synchronized SyncReport sync() {
        if (!enabled) {
            snapshot = Snapshot.empty();
            qdrantReady = false;
            lastReport = SyncReport.disabled();
            return lastReport;
        }
        long started = System.currentTimeMillis();
        Snapshot previous = snapshot;
        boolean qdrantWasReady = qdrantReady;
        qdrantReady = false;
        try {
            Snapshot next = loadSnapshot(previous.version());
            Diff diff = diff(previous, next);
            long version = diff.changed() ? previous.version() + 1 : previous.version();
            next = next.withVersion(version);
            snapshot = next;

            QdrantSyncResult qdrant = syncQdrant(next, diff, qdrantWasReady);
            lastReport = new SyncReport(true, version, next.units().size(),
                diff.added(), diff.updated(), diff.removed(),
                qdrant.synced(), qdrant.upserted(), qdrant.deleted(),
                System.currentTimeMillis() - started, Instant.now().toString(),
                null, qdrant.error());
            log.info("Semantic unit index sync completed: version={}, units={}, added={}, "
                    + "updated={}, removed={}, qdrantSynced={}",
                version, next.units().size(), diff.added(), diff.updated(), diff.removed(),
                qdrant.synced());
        } catch (Exception e) {
            // This index is supplemental. If authoritative state cannot be reloaded,
            // fail closed instead of serving a possibly revoked semantic unit.
            snapshot = new Snapshot(previous.version(), Collections.emptyMap());
            qdrantReady = false;
            lastReport = new SyncReport(false, previous.version(), 0,
                0, 0, 0, false, 0, 0,
                System.currentTimeMillis() - started, Instant.now().toString(),
                rootMessage(e), null);
            log.warn("Semantic unit index sync failed; disabling candidates at version {}: {}",
                previous.version(), rootMessage(e));
        }
        return lastReport;
    }

    public IndexStatus status() {
        if (!enabled) {
            return new IndexStatus(0, 0, DISABLED, false,
                disabledQdrantStatus(), lastReport);
        }
        Snapshot current = snapshot;
        return new IndexStatus(current.version(), current.units().size(),
            qdrantReady ? "qdrant" : "memory", qdrantReady,
            qdrantStore.lastKnownStatus(), lastReport);
    }

    public List<Map<String, Object>> search(List<Double> queryEmbedding, int topK,
                                            double minScore, Map<String, Object> filters) {
        if (!enabled) return Collections.emptyList();
        if (queryEmbedding == null || queryEmbedding.isEmpty() || topK <= 0) {
            return Collections.emptyList();
        }
        Map<String, Object> normalizedFilters = PayloadFilters.normalize(filters);
        if (PayloadFilters.isUnsatisfiable(normalizedFilters)
                || !TRUSTED_FILTER_FIELDS.containsAll(normalizedFilters.keySet())) {
            return Collections.emptyList();
        }

        if (qdrantReady && qdrantStore.isEnabled()) {
            try {
                List<QdrantVectorStore.SearchHit> hits = normalizedFilters.isEmpty()
                    ? qdrantStore.search(queryEmbedding, topK, minScore)
                    : qdrantStore.search(queryEmbedding, topK, minScore, normalizedFilters);
                Snapshot authoritative = snapshot;
                List<Map<String, Object>> results = new ArrayList<>(hits.size());
                Set<Long> seen = new HashSet<>();
                for (QdrantVectorStore.SearchHit hit : hits) {
                    Long unitId = semanticUnitId(hit.payload());
                    UnitEntry entry = unitId == null
                        ? null : authoritative.units().get(unitId);
                    if (entry == null || !seen.add(unitId)
                            || !Double.isFinite(hit.score()) || hit.score() < minScore) {
                        continue;
                    }
                    Map<String, Object> authoritativePayload = payload(entry);
                    if (!PayloadFilters.matches(authoritativePayload, normalizedFilters)) continue;
                    results.add(searchResult(authoritativePayload, hit.score()));
                    if (results.size() >= topK) break;
                }
                return results;
            } catch (Exception e) {
                qdrantReady = false;
                log.warn("Semantic unit Qdrant search failed; using memory fallback: {}",
                    rootMessage(e));
            }
        }
        return searchMemory(queryEmbedding, topK, minScore, normalizedFilters);
    }

    private List<Map<String, Object>> searchMemory(List<Double> queryEmbedding, int topK,
                                                    double minScore,
                                                    Map<String, Object> filters) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (UnitEntry entry : snapshot.units().values()) {
            Map<String, Object> payload = payload(entry);
            if (!PayloadFilters.matches(payload, filters)) continue;
            double similarity = cosineSimilarity(queryEmbedding, entry.vector());
            if (similarity < minScore) continue;
            results.add(searchResult(payload, similarity));
        }
        results.sort((left, right) -> Double.compare(
            number(right.get("similarity")), number(left.get("similarity"))));
        return results.size() > topK
            ? new ArrayList<>(results.subList(0, topK))
            : results;
    }

    private Snapshot loadSnapshot(long version) {
        List<BotKnowledgeSemanticUnit> units = unitMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeSemanticUnit>()
                .eq(BotKnowledgeSemanticUnit::getStatus, "APPROVED")
                .isNotNull(BotKnowledgeSemanticUnit::getEmbedding));
        List<BotKnowledgeChunk> chunks = chunkMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeChunk>()
                .eq(BotKnowledgeChunk::getStatus, "APPROVED"));
        List<BotKnowledgeDocument> documents = documentMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeDocument>()
                .eq(BotKnowledgeDocument::getStatus, 2));

        Map<Long, Long> approvedChunkDocuments = new HashMap<>();
        for (BotKnowledgeChunk chunk : chunks) {
            if (chunk.getId() != null && "APPROVED".equals(chunk.getStatus())
                    && !Integer.valueOf(1).equals(chunk.getDeleted())) {
                approvedChunkDocuments.put(chunk.getId(), chunk.getDocumentId());
            }
        }
        Map<Long, DocumentMeta> documentMetadata = new HashMap<>();
        for (BotKnowledgeDocument document : documents) {
            if (document.getId() == null || Integer.valueOf(1).equals(document.getDeleted())
                    || !Integer.valueOf(2).equals(document.getStatus())) continue;
            documentMetadata.put(document.getId(), new DocumentMeta(
                firstNonBlank(document.getTitle(), document.getFileName(),
                    "\u6587\u6863 " + document.getId()),
                document.getCategoryId(), nullToEmpty(document.getSourceScope()),
                document.getExpiresAt() == null
                    ? "" : document.getExpiresAt().toInstant().toString()));
        }

        Map<Long, UnitEntry> entries = new HashMap<>();
        for (BotKnowledgeSemanticUnit unit : units) {
            if (!"APPROVED".equals(unit.getStatus())
                    || Integer.valueOf(1).equals(unit.getDeleted())) continue;
            List<Double> vector = parseVector(unit.getEmbedding());
            if (unit.getId() == null || vector.isEmpty()) continue;
            if (nullToEmpty(unit.getQuestion()).isBlank()
                    && nullToEmpty(unit.getStatement()).isBlank()) continue;

            List<Long> declaredEvidenceChunkIds = parseLongList(
                unit.getEvidenceChunkIdsJson());
            boolean allEvidenceApproved = !declaredEvidenceChunkIds.isEmpty()
                && declaredEvidenceChunkIds.stream().allMatch(id ->
                    Objects.equals(unit.getDocumentId(), approvedChunkDocuments.get(id)));
            if (!allEvidenceApproved) {
                log.debug("Skipping semantic unit {} unless every evidence chunk remains "
                        + "approved and belongs to the same document",
                    unit.getId());
                continue;
            }
            List<Long> evidenceChunkIds = declaredEvidenceChunkIds.stream().distinct().toList();
            DocumentMeta document = documentMetadata.get(unit.getDocumentId());
            if (document == null) continue;
            Long categoryId = document.categoryId() != null
                ? document.categoryId() : unit.getCategoryId();
            if (categoryId == null) categoryId = 0L;
            entries.put(unit.getId(), new UnitEntry(
                unit.getId(), unit.getDocumentId(), categoryId,
                nullToEmpty(unit.getUnitKey()), nullToEmpty(unit.getUnitType()),
                nullToEmpty(unit.getQuestion()), nullToEmpty(unit.getStatement()),
                nullToEmpty(unit.getIntent()), nullToEmpty(unit.getEntitiesJson()),
                nullToEmpty(unit.getConditionsJson()), nullToEmpty(unit.getExclusionsJson()),
                nullToEmpty(unit.getQueryVariantsJson()), List.copyOf(evidenceChunkIds),
                nullToEmpty(unit.getSourceSpansJson()), nullToEmpty(unit.getMetadataJson()),
                unit.getExtractionConfidence(), nullToEmpty(unit.getExtractorModel()),
                nullToEmpty(unit.getPromptVersion()), nullToEmpty(unit.getSchemaVersion()),
                nullToEmpty(unit.getSourceHash()), document.title(), document.sourceScope(),
                document.expiresAt(), vector, nullToEmpty(unit.getEmbeddingModel()),
                nullToEmpty(unit.getEmbeddingVersion()), unit.getEmbeddingDimensions(),
                nullToEmpty(unit.getEmbeddingContentHash())));
        }
        return new Snapshot(version, Map.copyOf(entries));
    }

    private QdrantSyncResult syncQdrant(Snapshot next, Diff diff, boolean wasReady) {
        if (!qdrantStore.isEnabled()) return QdrantSyncResult.disabled();
        try {
            QdrantVectorStore.ReconcileResult result;
            if (!wasReady) {
                result = qdrantStore.reconcile(toPoints(next));
            } else {
                List<String> removedIds = diff.removedIds().stream()
                    .map(id -> QdrantVectorStore.pointId("semantic-unit:" + id))
                    .toList();
                result = qdrantStore.applyChanges(
                    toPoints(next, diff.changedIds()), removedIds, toPoints(next));
            }
            qdrantReady = true;
            return new QdrantSyncResult(true, result.upserted(), result.deleted(), null);
        } catch (Exception e) {
            qdrantReady = false;
            return new QdrantSyncResult(false, 0, 0, rootMessage(e));
        }
    }

    private List<QdrantVectorStore.VectorPoint> toPoints(Snapshot value) {
        return value.units().values().stream()
            .map(entry -> new QdrantVectorStore.VectorPoint(
                QdrantVectorStore.pointId("semantic-unit:" + entry.id()),
                entry.vector(), payload(entry)))
            .toList();
    }

    private List<QdrantVectorStore.VectorPoint> toPoints(Snapshot value, List<Long> ids) {
        List<QdrantVectorStore.VectorPoint> points = new ArrayList<>();
        for (Long id : ids) {
            UnitEntry entry = value.units().get(id);
            if (entry != null) {
                points.add(new QdrantVectorStore.VectorPoint(
                    QdrantVectorStore.pointId("semantic-unit:" + id),
                    entry.vector(), payload(entry)));
            }
        }
        return points;
    }

    private Map<String, Object> payload(UnitEntry entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "semantic_unit");
        payload.put("sourceType", "semantic_unit");
        payload.put("knowledgeType", "semantic_unit");
        payload.put("sourceId", entry.id());
        payload.put("semanticUnitId", entry.id());
        payload.put("documentId", entry.documentId());
        payload.put("documentTitle", entry.documentTitle());
        payload.put("categoryId", entry.categoryId());
        payload.put("sourceScope", entry.sourceScope());
        payload.put("expiresAt", entry.expiresAt());
        payload.put("unitKey", entry.unitKey());
        payload.put("unitType", entry.unitType());
        payload.put("question", entry.question());
        payload.put("statement", entry.statement());
        payload.put("intent", entry.intent());
        payload.put("entities", parseJson(entry.entitiesJson(), Collections.emptyList()));
        payload.put("conditions", parseJson(entry.conditionsJson(), Collections.emptyList()));
        payload.put("exclusions", parseJson(entry.exclusionsJson(), Collections.emptyList()));
        payload.put("queryVariants", parseJson(entry.queryVariantsJson(), Collections.emptyList()));
        payload.put("evidenceChunkIds", entry.evidenceChunkIds());
        payload.put("sourceSpans", parseJson(entry.sourceSpansJson(), Collections.emptyList()));
        payload.put("candidateMetadata", parseJsonMap(entry.metadataJson()));
        payload.put("extractionConfidence", entry.extractionConfidence());
        payload.put("extractorModel", entry.extractorModel());
        payload.put("promptVersion", entry.promptVersion());
        payload.put("schemaVersion", entry.schemaVersion());
        payload.put("sourceHash", entry.sourceHash());
        payload.put("candidateOnly", true);
        payload.put("requiresEvidence", true);
        addEmbeddingMetadata(payload, entry.embeddingModel(), entry.embeddingVersion(),
            entry.embeddingDimensions(), entry.embeddingContentHash());
        return payload;
    }

    private Map<String, Object> searchResult(Map<String, Object> payload, double similarity) {
        Map<String, Object> result = new LinkedHashMap<>(payload);
        PROHIBITED_RESULT_FIELDS.forEach(result::remove);
        result.put("candidateOnly", true);
        result.put("requiresEvidence", true);
        result.put("similarity", similarity);
        result.put("matchMode", "semantic_unit");
        return result;
    }

    private Long semanticUnitId(Map<String, Object> payload) {
        if (payload == null) return null;
        Long semanticUnitId = positiveLong(payload.get("semanticUnitId"));
        Long sourceId = positiveLong(payload.get("sourceId"));
        if (semanticUnitId != null && sourceId != null
                && !semanticUnitId.equals(sourceId)) {
            return null;
        }
        return semanticUnitId != null ? semanticUnitId : sourceId;
    }

    private Long positiveLong(Object value) {
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            long result = number.longValue();
            return Double.isFinite(numeric) && numeric == result && result > 0 ? result : null;
        }
        if (!(value instanceof String string) || !string.matches("[1-9]\\d*")) return null;
        try {
            return Long.valueOf(string);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Diff diff(Snapshot previous, Snapshot next) {
        int added = 0;
        int updated = 0;
        List<Long> changedIds = new ArrayList<>();
        List<Long> removedIds = new ArrayList<>();
        for (Map.Entry<Long, UnitEntry> entry : next.units().entrySet()) {
            if (!previous.units().containsKey(entry.getKey())) {
                added++;
                changedIds.add(entry.getKey());
            } else if (!Objects.equals(previous.units().get(entry.getKey()), entry.getValue())) {
                updated++;
                changedIds.add(entry.getKey());
            }
        }
        for (Long id : previous.units().keySet()) {
            if (!next.units().containsKey(id)) removedIds.add(id);
        }
        Collections.sort(changedIds);
        Collections.sort(removedIds);
        return new Diff(added, updated, removedIds.size(),
            List.copyOf(changedIds), List.copyOf(removedIds));
    }

    private List<Double> parseVector(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return List.copyOf(objectMapper.readValue(json, new TypeReference<List<Double>>() {}));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<Long> parseLongList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            List<?> values = objectMapper.readValue(json, new TypeReference<List<Object>>() {});
            List<Long> result = new ArrayList<>();
            for (Object value : values) {
                Long id = null;
                if (value instanceof Number number) id = number.longValue();
                else if (value instanceof String string && string.matches("\\d+")) {
                    id = Long.valueOf(string);
                }
                if (id == null || id <= 0) return Collections.emptyList();
                result.add(id);
            }
            return List.copyOf(result);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private Object parseJson(String json, Object fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return fallback;
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            Map<String, Object> value = objectMapper.readValue(
                json, new TypeReference<Map<String, Object>>() {});
            if (value == null) return Collections.emptyMap();
            Map<String, Object> sanitized = new LinkedHashMap<>(value);
            PROHIBITED_RESULT_FIELDS.forEach(sanitized::remove);
            return Collections.unmodifiableMap(sanitized);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private void addEmbeddingMetadata(Map<String, Object> payload, String model, String version,
                                      Integer dimensions, String contentHash) {
        if (!model.isBlank()) payload.put("embeddingModel", model);
        if (!version.isBlank()) payload.put("embeddingVersion", version);
        if (dimensions != null) payload.put("embeddingDimensions", dimensions);
        if (!contentHash.isBlank()) payload.put("embeddingContentHash", contentHash);
    }

    private double cosineSimilarity(List<Double> left, List<Double> right) {
        if (left.size() != right.size() || left.isEmpty()) return 0;
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.size(); i++) {
            double a = left.get(i);
            double b = right.get(i);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        return leftNorm == 0 || rightNorm == 0
            ? 0 : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static QdrantVectorStore.QdrantStatus disabledQdrantStatus() {
        return new QdrantVectorStore.QdrantStatus(
            false, false, "", "", 0, null, null, null, null,
            null, DISABLED);
    }

    private record Snapshot(long version, Map<Long, UnitEntry> units) {
        private static Snapshot empty() {
            return new Snapshot(0, Collections.emptyMap());
        }

        private Snapshot withVersion(long value) {
            return new Snapshot(value, units);
        }
    }

    private record UnitEntry(Long id, Long documentId, Long categoryId,
                             String unitKey, String unitType, String question, String statement,
                             String intent, String entitiesJson, String conditionsJson,
                             String exclusionsJson, String queryVariantsJson,
                             List<Long> evidenceChunkIds, String sourceSpansJson,
                             String metadataJson, Double extractionConfidence,
                             String extractorModel, String promptVersion, String schemaVersion,
                             String sourceHash, String documentTitle, String sourceScope,
                             String expiresAt, List<Double> vector, String embeddingModel,
                             String embeddingVersion, Integer embeddingDimensions,
                             String embeddingContentHash) {}

    private record DocumentMeta(String title, Long categoryId, String sourceScope,
                                String expiresAt) {}

    private record Diff(int added, int updated, int removed,
                        List<Long> changedIds, List<Long> removedIds) {
        private boolean changed() {
            return added > 0 || updated > 0 || removed > 0;
        }
    }

    private record QdrantSyncResult(boolean synced, int upserted, int deleted, String error) {
        private static QdrantSyncResult disabled() {
            return new QdrantSyncResult(false, 0, 0, null);
        }
    }

    public record SyncReport(boolean success, long version, int units,
                             int added, int updated, int removed,
                             boolean qdrantSynced, int qdrantUpserted, int qdrantDeleted,
                             long durationMs, String syncedAt, String error,
                             String qdrantError) {
        private static SyncReport empty() {
            return new SyncReport(false, 0, 0, 0, 0, 0,
                false, 0, 0, 0, null, "Not synchronized yet", null);
        }

        private static SyncReport disabled() {
            return new SyncReport(false, 0, 0, 0, 0, 0,
                false, 0, 0, 0, null, DISABLED, null);
        }
    }

    public record IndexStatus(long version, int units, String searchBackend,
                              boolean qdrantReady, QdrantVectorStore.QdrantStatus qdrant,
                              SyncReport lastSync) {}
}
