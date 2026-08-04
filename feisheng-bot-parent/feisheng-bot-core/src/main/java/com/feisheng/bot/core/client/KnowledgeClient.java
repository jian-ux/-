package com.feisheng.bot.core.client;

import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.knowledge.controller.KnowledgeItemController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class KnowledgeClient {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeClient.class);
    private final KnowledgeItemController knowledgeItemController;

    public KnowledgeClient(KnowledgeItemController kic) {
        this.knowledgeItemController = kic;
    }

    /** Direct call: keyword match (no longer HTTP) */
    public Map<String, Object> match(String text) {
        return match(text, true);
    }

    /** Direct call: keyword match with optional hit-count tracking. */
    public Map<String, Object> match(String text, boolean trackHit) {
        return match(text, trackHit, Collections.emptyMap());
    }

    /** Direct call: keyword match constrained by trusted knowledge metadata. */
    public Map<String, Object> match(String text, boolean trackHit,
                                     Map<String, Object> filters) {
        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        body.put("trackHit", trackHit);
        putFilters(body, filters);
        try {
            R<Map<String, Object>> r = knowledgeItemController.match(body);
            if (r != null && r.getData() != null && !r.getData().isEmpty()) {
                return r.getData();
            }
        } catch (Exception e) {
            log.warn("Knowledge match failed: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    /** Direct call: semantic match */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> semanticMatch(String text, List<Double> embedding, int topK) {
        return semanticMatch(text, embedding, topK, Collections.emptyMap());
    }

    /** Direct call: semantic match constrained by trusted knowledge metadata. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> semanticMatch(String text, List<Double> embedding, int topK,
                                                   Map<String, Object> filters) {
        if (embedding == null || embedding.isEmpty()) return Collections.emptyList();
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("embedding", embedding);
            body.put("topK", topK);
            putFilters(body, filters);
            R<List<Map<String, Object>>> r = knowledgeItemController.semanticMatch(body);
            if (r != null && r.getData() != null) {
                return r.getData();
            }
        } catch (Exception e) {
            log.warn("Semantic match failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /** Direct call: conservative pinyin fallback for homophone-heavy Chinese input. */
    public List<Map<String, Object>> phoneticMatch(String text, int topK, double minScore) {
        return phoneticMatch(text, topK, minScore, Collections.emptyMap());
    }

    /** Direct call: metadata-constrained pinyin fallback. */
    public List<Map<String, Object>> phoneticMatch(String text, int topK, double minScore,
                                                   Map<String, Object> filters) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("text", text);
            body.put("topK", topK);
            body.put("minScore", minScore);
            putFilters(body, filters);
            R<List<Map<String, Object>>> r = knowledgeItemController.phoneticMatch(body);
            if (r != null && r.getData() != null) return r.getData();
        } catch (Exception e) {
            log.warn("Phonetic match failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /** Direct call: text-similarity fallback when query embedding is unavailable. */
    public List<Map<String, Object>> lexicalMatch(String text, int topK, double minScore) {
        return lexicalMatch(text, topK, minScore, Collections.emptyMap());
    }

    /** Direct call: metadata-constrained text-similarity fallback. */
    public List<Map<String, Object>> lexicalMatch(String text, int topK, double minScore,
                                                  Map<String, Object> filters) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("text", text);
            body.put("topK", topK);
            body.put("minScore", minScore);
            putFilters(body, filters);
            R<List<Map<String, Object>>> r = knowledgeItemController.lexicalMatch(body);
            if (r != null && r.getData() != null) return r.getData();
        } catch (Exception e) {
            log.warn("Lexical match failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /** Sparse BM25 recall. Scores are used for ranking, not answerability calibration. */
    public List<Map<String, Object>> bm25Match(String text, int topK, double minScore) {
        return bm25Match(text, topK, minScore, Collections.emptyMap());
    }

    /** Metadata-constrained sparse BM25 recall. */
    public List<Map<String, Object>> bm25Match(String text, int topK, double minScore,
                                               Map<String, Object> filters) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("text", text);
            body.put("topK", topK);
            body.put("minScore", minScore);
            putFilters(body, filters);
            R<List<Map<String, Object>>> r = knowledgeItemController.bm25Match(body);
            if (r != null && r.getData() != null) return r.getData();
        } catch (Exception e) {
            log.warn("BM25 match failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /** Fetches approved chunks adjacent to an already accepted document chunk. */
    public List<Map<String, Object>> neighborChunks(Long documentId, Integer chunkIndex, int radius) {
        return neighborChunks(documentId, chunkIndex, radius, null);
    }

    /** Fetches adjacent chunks without crossing a known section boundary. */
    public List<Map<String, Object>> neighborChunks(Long documentId, Integer chunkIndex, int radius,
                                                    String sectionPath) {
        if (documentId == null || chunkIndex == null || radius <= 0) {
            return Collections.emptyList();
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("documentId", documentId);
            body.put("chunkIndex", chunkIndex);
            body.put("radius", radius);
            if (sectionPath != null && !sectionPath.isBlank()) body.put("sectionPath", sectionPath);
            R<List<Map<String, Object>>> r = knowledgeItemController.neighbors(body);
            if (r != null && r.getData() != null) return r.getData();
        } catch (Exception e) {
            log.warn("Neighbor chunk lookup failed for document {} chunk {}: {}",
                documentId, chunkIndex, e.getMessage());
        }
        return Collections.emptyList();
    }

    private void putFilters(Map<String, Object> body, Map<String, Object> filters) {
        if (filters != null && !filters.isEmpty()) body.put("filters", Map.copyOf(filters));
    }

    /** Direct call: update embedding */
    public boolean updateEmbedding(Long itemId, List<Double> embedding) {
        return updateEmbedding(itemId, embedding, "", "", "");
    }

    public boolean updateEmbedding(Long itemId, List<Double> embedding,
                                   String model, String version, String contentHash) {
        if (itemId == null || embedding == null || embedding.isEmpty()) return false;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("id", itemId);
            body.put("embedding", embedding);
            body.put("embeddingModel", model);
            body.put("embeddingVersion", version);
            body.put("embeddingDimensions", embedding.size());
            body.put("embeddingContentHash", contentHash);
            R<Void> r = knowledgeItemController.updateEmbedding(body);
            return r != null && r.getCode() == 200;
        } catch (Exception e) {
            log.warn("Update embedding failed for item {}: {}", itemId, e.getMessage());
            return false;
        }
    }

    /** Direct call: get pending embedding items */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getPendingEmbeddingItems() {
        try {
            R<List<com.feisheng.bot.knowledge.entity.BotKnowledgeItem>> r =
                knowledgeItemController.getPendingEmbedding();
            if (r != null && r.getData() != null) {
                // Convert entities to maps for backward compatibility
                List<Map<String, Object>> result = new ArrayList<>();
                for (var item : r.getData()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", item.getId());
                    m.put("question", item.getQuestion());
                    m.put("answer", item.getAnswer());
                    m.put("keywords", item.getKeywords());
                    m.put("categoryId", item.getCategoryId());
                    m.put("status", item.getStatus());
                    result.add(m);
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("Get pending embedding items failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /** Direct call: force refresh vector cache */
    public void refreshVectorCache() {
        try {
            knowledgeItemController.refreshVectorCache();
        } catch (Exception e) {
            log.warn("Refresh vector cache failed: {}", e.getMessage());
        }
    }
}
