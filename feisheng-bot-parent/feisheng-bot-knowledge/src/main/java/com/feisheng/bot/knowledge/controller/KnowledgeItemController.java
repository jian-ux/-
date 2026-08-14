package com.feisheng.bot.knowledge.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.knowledge.entity.BotKnowledgeChunk;
import com.feisheng.bot.knowledge.entity.BotKnowledgeItem;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeItemMapper;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.knowledge.service.KnowledgeIndexService;
import com.feisheng.bot.knowledge.service.PayloadFilters;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.common.vo.R;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/knowledge/item")
public class KnowledgeItemController {
    private final BotKnowledgeItemMapper mapper;
    private final BotKnowledgeChunkMapper chunkMapper;
    private final KnowledgeIndexService indexService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KnowledgeItemController(BotKnowledgeItemMapper m, BotKnowledgeChunkMapper chunkMapper,
                                   KnowledgeIndexService indexService) {
        mapper = m;
        this.chunkMapper = chunkMapper;
        this.indexService = indexService;
    }

    // ==================== CRUD ====================

    @PostMapping("/add")
    public R<Void> add(@RequestBody BotKnowledgeItem i) {
        mapper.insert(i);
        indexService.sync();
        return R.ok();
    }

    @PutMapping("/update")
    public R<Void> update(@RequestBody BotKnowledgeItem i) {
        mapper.updateById(i);
        indexService.sync();
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        mapper.deleteById(id);
        indexService.sync();
        return R.ok();
    }

    @GetMapping("/search")
    public R<Page<BotKnowledgeItem>> search(
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) String keyword) {
        LambdaQueryWrapper<BotKnowledgeItem> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword))
            q.and(w -> w.like(BotKnowledgeItem::getQuestion, keyword)
                         .or().like(BotKnowledgeItem::getKeywords, keyword));
        q.eq(BotKnowledgeItem::getStatus, 1).orderByDesc(BotKnowledgeItem::getHitCount);
        return R.ok(mapper.selectPage(new Page<>(page, size), q));
    }

    // ==================== Keyword match (P1-1: improved) ====================

    /** Chinese negative prefixes that invert keyword meaning */
    private static final Set<String> NEGATIVE_PREFIXES = Set.of(
        "\u4e0d", "\u6ca1", "\u672a", "\u975e", "\u65e0", "\u522b", "\u83ab", "\u52ff"
    );
    private static final Pattern QUESTION_SEPARATORS = Pattern.compile("[\\p{P}\\p{S}\\s]+");
    private static final Pattern EXPLICIT_QUESTION_ALIAS =
        Pattern.compile("[^?？,，;；\\r\\n]+[?？]");
    private static final Pattern LEADING_CONNECTORS = Pattern.compile("^[的地得]+");
    private static final List<String> QUESTION_FILLER_PREFIXES = List.of(
        "请问一下", "麻烦问下", "想问一下", "我想问", "请问",
        "你们公司", "我们公司", "贵公司", "我们", "你们"
    );
    private static final String[][] QUESTION_EQUIVALENT_PHRASES = {
        {"主要是做什么的", "是什么"},
        {"主要是做什么", "是什么"},
        {"主要做什么的", "是什么"},
        {"主要做什么", "是什么"},
        {"是干什么的", "是什么"},
        {"干什么的", "是什么"},
        {"是做什么的", "是什么"},
        {"做什么的", "是什么"}
    };

    @PostMapping("/match")
    public R<Map<String, Object>> match(@RequestBody Map<String, Object> body) {
        String text = Objects.toString(body.get("text"), "");
        boolean trackHit = !Boolean.FALSE.equals(body.get("trackHit"));
        if (!StringUtils.hasText(text)) return R.ok(Collections.emptyMap());

        List<BotKnowledgeItem> items = mapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeItem>().eq(BotKnowledgeItem::getStatus, 1));
        Map<String, Object> trustedFilters = filters(body);

        Map<String, Object> bestMatch = null;
        double bestScore = 0;

        for (BotKnowledgeItem i : items) {
            Map<String, Object> candidatePayload = faqMatchPayload(i);
            if (!PayloadFilters.matchesPayload(candidatePayload, trustedFilters)) continue;
            KeywordMatchScore match = scoreKeywordMatch(text, i);
            if (match.score() > bestScore) {
                bestScore = match.score();
                Map<String, Object> r = new LinkedHashMap<>(candidatePayload);
                r.put("confidence", match.score() >= 0.8 ? "high" : "medium");
                r.put("score", match.score());
                r.put("exactMatch", match.exactQuestion());
                r.put("matchMode", match.matchMode());
                r.put("directAnswerEnabled",
                    Integer.valueOf(1).equals(i.getDirectAnswerEnabled()));
                bestMatch = r;
            }
        }

        // Update hit count for the best match
        if (trackHit && bestMatch != null) {
            Long itemId = ((Number) bestMatch.get("itemId")).longValue();
            BotKnowledgeItem item = mapper.selectById(itemId);
            if (item != null) {
                item.setHitCount(item.getHitCount() == null ? 1 : item.getHitCount() + 1);
                mapper.updateById(item);
            }
        }

        return R.ok(bestMatch != null ? bestMatch : Collections.emptyMap());
    }

    private Map<String, Object> faqMatchPayload(BotKnowledgeItem item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "item");
        payload.put("sourceType", "faq");
        payload.put("sourceScope", "KNOWLEDGE");
        payload.put("sourceId", item.getId());
        payload.put("itemId", item.getId());
        if (item.getCategoryId() != null) payload.put("categoryId", item.getCategoryId());
        payload.put("question", item.getQuestion());
        payload.put("answer", item.getAnswer());
        payload.put("keywords", item.getKeywords());
        payload.put("status", item.getStatus());
        payload.put("directAnswerEnabled",
            Integer.valueOf(1).equals(item.getDirectAnswerEnabled()));
        return payload;
    }

    /**
     * Score keyword match quality. Returns 0 if no match.
     * Considers: keyword coverage, negative word detection, question similarity.
     */
    private KeywordMatchScore scoreKeywordMatch(String text, BotKnowledgeItem item) {
        String lowerText = text.toLowerCase();
        double score = 0;
        String matchMode = "none";

        String normalizedText = normalizeQuestion(lowerText);
        String normalizedQuestion = normalizeQuestion(item.getQuestion());
        String kw = item.getKeywords();
        boolean exactStandardQuestion = !normalizedText.isEmpty()
            && normalizedText.equals(normalizedQuestion);
        boolean exactAlias = matchesExplicitQuestionAlias(normalizedText, kw);
        boolean exactQuestion = exactStandardQuestion || exactAlias;
        if (exactStandardQuestion) {
            score = 1.0;
            matchMode = "exact_question";
        } else if (exactAlias) {
            score = 1.0;
            matchMode = "exact_alias";
        } else if (normalizedText.length() >= 4 && normalizedQuestion.length() >= 4
                && (normalizedText.contains(normalizedQuestion)
                    || normalizedQuestion.contains(normalizedText))) {
            // Containment often means a generic FAQ matched only the intent suffix,
            // e.g. "点签主要做什么" matching "公司做什么". It may be useful
            // context, but it is not safe for a direct canned answer.
            score = 0.76;
            matchMode = "question_containment";
        }

        // 1. Keyword matching with negative word detection
        if (StringUtils.hasText(kw)) {
            String[] keywords = splitKeywords(kw);
            int totalKw = keywords.length;
            int hitKw = 0;
            int longestHitChars = 0;
            boolean negated = false;

            for (String k : keywords) {
                String keyword = k.trim().toLowerCase();
                if (keyword.isEmpty()) continue;

                int idx = lowerText.indexOf(keyword);
                if (idx >= 0) {
                    // Check for negative prefix before the keyword
                    if (idx > 0 && NEGATIVE_PREFIXES.contains(String.valueOf(lowerText.charAt(idx - 1)))) {
                        negated = true;
                    } else {
                        hitKw++;
                        String normalizedKeyword = QUESTION_SEPARATORS.matcher(keyword).replaceAll("");
                        longestHitChars = Math.max(longestHitChars, normalizedKeyword.length());
                    }
                }
            }

            if (negated && hitKw == 0) {
                return new KeywordMatchScore(0, false, "negated");
            }
            if (totalKw > 0 && hitKw > 0) {
                // Keywords recall candidates; only an equivalent FAQ question may
                // cross the direct-answer threshold.
                double coverageScore = 0.55 + 0.25 * ((double) hitKw / totalKw);
                double phraseScore = longestHitChars >= 6 ? 0.80
                    : longestHitChars >= 4 ? 0.76
                    : longestHitChars >= 3 ? 0.72 : 0;
                double keywordScore = Math.max(coverageScore, phraseScore);
                if (keywordScore > score) {
                    score = keywordScore;
                    matchMode = "keyword";
                }
            }
        }

        // 2. Question prefix matching (improved: use 10 chars instead of 5)
        String question = item.getQuestion();
        if (StringUtils.hasText(question)) {
            String q = question.toLowerCase();
            int prefixLen = Math.min(10, q.length());
            String prefix = q.substring(0, prefixLen);
            if (lowerText.contains(prefix)) {
                if (score < 0.5) {
                    score = 0.5;
                    matchMode = "question_prefix";
                }
            } else {
                // Character overlap for the first portion
                int overlap = 0;
                for (int i = 0; i < prefixLen; i++) {
                    if (lowerText.indexOf(q.charAt(i)) >= 0) overlap++;
                }
                double overlapRatio = (double) overlap / prefixLen;
                if (overlapRatio >= 0.7) {
                    double overlapScore = overlapRatio * 0.4;
                    if (overlapScore > score) {
                        score = overlapScore;
                        matchMode = "character_overlap";
                    }
                }
            }
        }

        return new KeywordMatchScore(score, exactQuestion, matchMode);
    }

    private boolean matchesExplicitQuestionAlias(String normalizedText, String keywords) {
        if (normalizedText.isEmpty() || !StringUtils.hasText(keywords)) return false;
        var aliases = EXPLICIT_QUESTION_ALIAS.matcher(keywords);
        while (aliases.find()) {
            String alias = aliases.group().trim();
            if (normalizedText.equals(normalizeQuestion(alias))) return true;
        }
        return false;
    }

    private String[] splitKeywords(String value) {
        return value.split("[,，;；\\r\\n]+");
    }

    private String normalizeQuestion(String value) {
        if (!StringUtils.hasText(value)) return "";
        String normalized = QUESTION_SEPARATORS.matcher(value.toLowerCase()).replaceAll("");
        boolean changed;
        do {
            changed = false;
            for (String prefix : QUESTION_FILLER_PREFIXES) {
                if (normalized.startsWith(prefix) && normalized.length() > prefix.length()) {
                    normalized = normalized.substring(prefix.length());
                    normalized = LEADING_CONNECTORS.matcher(normalized).replaceFirst("");
                    changed = true;
                    break;
                }
            }
        } while (changed);
        for (String[] equivalent : QUESTION_EQUIVALENT_PHRASES) {
            normalized = normalized.replace(equivalent[0], equivalent[1]);
        }
        return normalized;
    }

    private record KeywordMatchScore(double score, boolean exactQuestion, String matchMode) {}

    // ==================== Semantic match (P0-1: FAQ items + document chunks) ====================

    @PostMapping("/semantic-match")
    public R<List<Map<String, Object>>> semanticMatch(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Double> queryEmbedding = (List<Double>) body.get("embedding");
        int topK = body.containsKey("topK") ? ((Number) body.get("topK")).intValue() : 3;
        double minScore = body.containsKey("minScore") ? ((Number) body.get("minScore")).doubleValue() : -1;

        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            return R.ok(Collections.emptyList());
        }

        return R.ok(indexService.search(queryEmbedding, topK, minScore, filters(body)));
    }

    @PostMapping("/phonetic-match")
    public R<List<Map<String, Object>>> phoneticMatch(@RequestBody Map<String, Object> body) {
        String text = Objects.toString(body.get("text"), "");
        int topK = body.containsKey("topK") ? ((Number) body.get("topK")).intValue() : 3;
        double minScore = body.containsKey("minScore")
            ? ((Number) body.get("minScore")).doubleValue() : 0.80;
        return R.ok(indexService.searchPhonetic(text, topK, minScore, filters(body)));
    }

    @PostMapping("/lexical-match")
    public R<List<Map<String, Object>>> lexicalMatch(@RequestBody Map<String, Object> body) {
        String text = Objects.toString(body.get("text"), "");
        int topK = body.containsKey("topK") ? ((Number) body.get("topK")).intValue() : 3;
        double minScore = body.containsKey("minScore")
            ? ((Number) body.get("minScore")).doubleValue() : 0.72;
        return R.ok(indexService.searchLexical(text, topK, minScore, filters(body)));
    }

    @PostMapping("/bm25-match")
    public R<List<Map<String, Object>>> bm25Match(@RequestBody Map<String, Object> body) {
        String text = Objects.toString(body.get("text"), "");
        int topK = body.containsKey("topK") ? ((Number) body.get("topK")).intValue() : 10;
        double minScore = body.containsKey("minScore")
            ? ((Number) body.get("minScore")).doubleValue() : 0.0;
        return R.ok(indexService.searchBm25(text, topK, minScore, filters(body)));
    }

    @PostMapping("/neighbors")
    public R<List<Map<String, Object>>> neighbors(@RequestBody Map<String, Object> body) {
        if (!(body.get("documentId") instanceof Number documentIdValue)
                || !(body.get("chunkIndex") instanceof Number chunkIndexValue)) {
            return R.ok(Collections.emptyList());
        }
        long documentId = documentIdValue.longValue();
        int chunkIndex = chunkIndexValue.intValue();
        int radius = body.get("radius") instanceof Number value
            ? Math.max(0, Math.min(2, value.intValue())) : 1;
        if (radius == 0) return R.ok(Collections.emptyList());
        String sectionPath = body.containsKey("sectionPath") && body.get("sectionPath") != null
            ? Objects.toString(body.get("sectionPath")) : null;

        LambdaQueryWrapper<BotKnowledgeChunk> query = new LambdaQueryWrapper<BotKnowledgeChunk>()
            .eq(BotKnowledgeChunk::getDocumentId, documentId)
            .eq(BotKnowledgeChunk::getStatus, "APPROVED")
            .ge(BotKnowledgeChunk::getChunkIndex, chunkIndex - radius)
            .le(BotKnowledgeChunk::getChunkIndex, chunkIndex + radius);
        if (sectionPath != null) {
            if (sectionPath.isBlank()) {
                query.and(nested -> nested.isNull(BotKnowledgeChunk::getSectionPath)
                    .or().eq(BotKnowledgeChunk::getSectionPath, ""));
            } else {
                query.eq(BotKnowledgeChunk::getSectionPath, sectionPath);
            }
        }
        query.orderByAsc(BotKnowledgeChunk::getChunkIndex);
        List<BotKnowledgeChunk> chunks = chunkMapper.selectList(query);
        List<Map<String, Object>> result = new ArrayList<>();
        for (BotKnowledgeChunk chunk : chunks) {
            if (Objects.equals(chunk.getChunkIndex(), chunkIndex)) continue;
            if (sectionPath != null
                    && !Objects.equals(sectionPath, Objects.toString(chunk.getSectionPath(), ""))) {
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", "chunk");
            value.put("chunkId", chunk.getId());
            value.put("sourceId", chunk.getId());
            value.put("documentId", chunk.getDocumentId());
            value.put("chunkIndex", chunk.getChunkIndex());
            value.put("content", chunk.getContent());
            value.put("sectionPath", chunk.getSectionPath());
            value.put("charCount", chunk.getCharCount());
            value.put("chunkStrategyVersion", chunk.getChunkStrategyVersion());
            if ("QA".equals(chunk.getContentType())) {
                value.put("structuredQa", true);
                value.put("knowledgeType", "structured_qa");
                value.put("question", chunk.getQaQuestion());
                value.put("answer", chunk.getQaAnswer());
                value.put("fullAnswer", chunk.getQaAnswer());
                value.put("qaKey", chunk.getQaKey());
                value.put("qaGroupKey", chunk.getQaGroupKey());
                value.put("qaVersion", chunk.getQaVersion());
            }
            result.add(value);
        }
        return R.ok(result);
    }

    private Map<String, Object> filters(Map<String, Object> body) {
        if (!(body.get("filters") instanceof Map<?, ?> rawFilters) || rawFilters.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> filters = new LinkedHashMap<>();
        rawFilters.forEach((key, value) -> {
            if (key instanceof String stringKey) filters.put(stringKey, value);
        });
        return filters;
    }

    // ==================== Embedding management ====================

    @PostMapping("/update-embedding")
    public R<Void> updateEmbedding(@RequestBody Map<String, Object> body) {
        Object idObj = body.get("id");
        if (idObj == null) return R.fail(400, "条目标识不能为空");
        Long id = ((Number) idObj).longValue();
        @SuppressWarnings("unchecked")
        List<Double> embedding = (List<Double>) body.get("embedding");
        if (embedding == null) return R.fail(400, "向量数据不能为空");

        try {
            String embeddingJson = objectMapper.writeValueAsString(embedding);
            BotKnowledgeItem item = mapper.selectById(id);
            if (item == null) return R.fail(404, "\u6761\u76ee\u4e0d\u5b58\u5728");
            item.setEmbedding(embeddingJson);
            item.setEmbeddingModel(Objects.toString(body.get("embeddingModel"), null));
            item.setEmbeddingVersion(Objects.toString(body.get("embeddingVersion"), null));
            item.setEmbeddingDimensions(body.get("embeddingDimensions") instanceof Number number
                ? number.intValue() : embedding.size());
            item.setEmbeddingContentHash(Objects.toString(body.get("embeddingContentHash"), null));
            mapper.updateById(item);
            indexService.sync();
            return R.ok();
        } catch (Exception e) {
            return R.fail(500, "更新向量数据失败");
        }
    }

    @GetMapping("/pending-embedding")
    public R<List<BotKnowledgeItem>> getPendingEmbedding() {
        List<BotKnowledgeItem> items = mapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeItem>()
                .isNull(BotKnowledgeItem::getEmbedding)
                .eq(BotKnowledgeItem::getStatus, 1));
        return R.ok(items);
    }

    /** Force refresh vector cache (called after bulk updates) */
    @PostMapping("/refresh-cache")
    public R<Void> refreshVectorCache() {
        indexService.sync();
        return R.ok();
    }

    @PostMapping("/sync")
    public R<KnowledgeIndexService.SyncReport> syncIndex() {
        return R.ok(indexService.sync());
    }

    @GetMapping("/sync-status")
    public R<KnowledgeIndexService.IndexStatus> syncStatus() {
        return R.ok(indexService.status());
    }

    // ==================== Utilities ====================

}
