package com.feisheng.bot.core.client;

import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.knowledge.controller.KnowledgeChunkController;
import com.feisheng.bot.knowledge.controller.SemanticUnitController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Client for the candidate-only semantic-unit index. Semantic-unit text is
 * deliberately not exposed: callers must resolve evidence chunk IDs before
 * using a hit for reranking or answer generation.
 */
@Component
public class StructuredUnitRetrievalClient {
    private static final Logger log = LoggerFactory.getLogger(StructuredUnitRetrievalClient.class);
    private static final int MAX_EVIDENCE_CHUNKS = 50;

    private final SemanticUnitController semanticUnitController;
    private final KnowledgeChunkController knowledgeChunkController;

    public StructuredUnitRetrievalClient(SemanticUnitController semanticUnitController,
                                         KnowledgeChunkController knowledgeChunkController) {
        this.semanticUnitController = semanticUnitController;
        this.knowledgeChunkController = knowledgeChunkController;
    }

    public List<StructuredUnitHit> search(List<Double> embedding, int topK,
                                          Map<String, Object> filters) {
        if (embedding == null || embedding.isEmpty() || topK <= 0) {
            return Collections.emptyList();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("embedding", List.copyOf(embedding));
        body.put("topK", Math.min(topK, MAX_EVIDENCE_CHUNKS));
        putFilters(body, filters);
        try {
            R<List<Map<String, Object>>> response = semanticUnitController.semanticUnitSearch(body);
            if (response == null || response.getData() == null) return Collections.emptyList();
            List<StructuredUnitHit> hits = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Map<String, Object> candidate : response.getData()) {
                if (candidate == null || !Boolean.TRUE.equals(candidate.get("candidateOnly"))) {
                    continue;
                }
                String unitId = Objects.toString(candidate.get("semanticUnitId"), "").trim();
                double score = number(candidate.containsKey("score")
                    ? candidate.get("score") : candidate.get("similarity"));
                List<Long> evidenceChunkIds = longIds(candidate.get("evidenceChunkIds"));
                if (unitId.isEmpty() || !Double.isFinite(score) || evidenceChunkIds.isEmpty()
                        || !seen.add(unitId)) {
                    continue;
                }
                hits.add(new StructuredUnitHit(unitId, score, evidenceChunkIds));
            }
            return List.copyOf(hits);
        } catch (Exception e) {
            log.warn("Structured-unit search failed; keeping evidence-index results: {}",
                e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> evidenceChunks(List<Long> chunkIds,
                                                     Map<String, Object> filters) {
        List<Long> requested = chunkIds == null ? Collections.emptyList()
            : chunkIds.stream().filter(Objects::nonNull).distinct()
                .limit(MAX_EVIDENCE_CHUNKS).toList();
        if (requested.isEmpty()) return Collections.emptyList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chunkIds", requested);
        putFilters(body, filters);
        try {
            R<List<Map<String, Object>>> response = knowledgeChunkController.evidenceChunks(body);
            if (response == null || response.getData() == null) return Collections.emptyList();
            Set<Long> allowed = Set.copyOf(requested);
            Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
            for (Map<String, Object> chunk : response.getData()) {
                if (chunk == null) continue;
                Long chunkId = longValue(chunk.get("chunkId"));
                if (chunkId == null) chunkId = longValue(chunk.get("sourceId"));
                if (chunkId == null || !allowed.contains(chunkId)) continue;
                Map<String, Object> evidence = new LinkedHashMap<>(chunk);
                evidence.put("type", "chunk");
                evidence.put("chunkId", chunkId);
                evidence.put("sourceId", chunkId);
                removeDirectAnswerFields(evidence);
                byId.putIfAbsent(chunkId, evidence);
            }
            List<Map<String, Object>> ordered = new ArrayList<>();
            for (Long requestedId : requested) {
                Map<String, Object> chunk = byId.get(requestedId);
                if (chunk != null) {
                    ordered.add(Collections.unmodifiableMap(new LinkedHashMap<>(chunk)));
                }
            }
            return List.copyOf(ordered);
        } catch (Exception e) {
            log.warn("Structured-unit evidence lookup failed; keeping evidence-index results: {}",
                e.getMessage());
            return Collections.emptyList();
        }
    }

    private void removeDirectAnswerFields(Map<String, Object> evidence) {
        evidence.remove("answer");
        evidence.remove("fullAnswer");
        evidence.remove("directAnswerEnabled");
        evidence.remove("directAnswerEligible");
        evidence.remove("structuredQaExactMatch");
        evidence.put("structuredQa", false);
    }

    private void putFilters(Map<String, Object> body, Map<String, Object> filters) {
        if (filters != null && !filters.isEmpty()) body.put("filters", Map.copyOf(filters));
    }

    private List<Long> longIds(Object value) {
        if (!(value instanceof List<?> values)) return Collections.emptyList();
        if (values.size() > MAX_EVIDENCE_CHUNKS) return Collections.emptyList();
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (Object item : values) {
            Long id = longValue(item);
            if (id != null) result.add(id);
            if (result.size() > MAX_EVIDENCE_CHUNKS) return Collections.emptyList();
        }
        return List.copyOf(result);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    public record StructuredUnitHit(String semanticUnitId, double score,
                                    List<Long> evidenceChunkIds) {
        public StructuredUnitHit {
            semanticUnitId = Objects.requireNonNull(semanticUnitId, "semanticUnitId");
            evidenceChunkIds = evidenceChunkIds == null
                ? Collections.emptyList() : List.copyOf(evidenceChunkIds);
        }
    }
}
