package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeItem;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeItemMapper;
import com.feisheng.bot.admin.util.VectorUtil;
import com.feisheng.bot.common.util.KnowledgeTextUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VectorSearchService {
    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);

    private final BotKnowledgeItemMapper itemMapper;
    private final BotKnowledgeChunkMapper chunkMapper;
    private final EmbeddingService embeddingService;

    /** id -> embedding vector (both items and chunks, keyed as "item:123" or "chunk:456") */
    private final Map<String, float[]> vectorCache = new ConcurrentHashMap<>();

    public VectorSearchService(BotKnowledgeItemMapper itemMapper,
                                BotKnowledgeChunkMapper chunkMapper,
                                EmbeddingService embeddingService) {
        this.itemMapper = itemMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingService = embeddingService;
    }

    @PostConstruct
    public void loadVectors() {
        // Load FAQ items
        try {
            List<BotKnowledgeItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<BotKnowledgeItem>()
                    .eq(BotKnowledgeItem::getStatus, 1)
                    .isNotNull(BotKnowledgeItem::getEmbedding));
            int itemLoaded = 0;
            for (BotKnowledgeItem item : items) {
                float[] vec = VectorUtil.fromJson(item.getEmbedding());
                if (vec.length > 0) { vectorCache.put("item:" + item.getId(), vec); itemLoaded++; }
            }
            log.info("Loaded {} FAQ item embeddings", itemLoaded);
        } catch (Exception e) {
            log.warn("FAQ embedding load failed: {}", e.getMessage());
        }
        // P3-2: Load only APPROVED chunks (previously loaded ALL chunks regardless of status)
        try {
            List<BotKnowledgeChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<BotKnowledgeChunk>()
                    .eq(BotKnowledgeChunk::getStatus, "APPROVED")
                    .isNotNull(BotKnowledgeChunk::getEmbedding));
            int chunkLoaded = 0;
            for (BotKnowledgeChunk c : chunks) {
                float[] vec = VectorUtil.fromJson(c.getEmbedding());
                if (vec.length > 0) { vectorCache.put("chunk:" + c.getId(), vec); chunkLoaded++; }
            }
            log.info("Loaded {} APPROVED chunk embeddings", chunkLoaded);
        } catch (Exception e) {
            log.warn("Chunk embedding load failed: {}", e.getMessage());
        }
    }

    public void reloadItem(Long itemId) {
        BotKnowledgeItem item = itemMapper.selectById(itemId);
        if (item != null && item.getEmbedding() != null) {
            float[] vec = VectorUtil.fromJson(item.getEmbedding());
            if (vec.length > 0) vectorCache.put("item:" + itemId, vec);
            else vectorCache.remove("item:" + itemId);
        }
    }

    public void removeItem(Long itemId) { vectorCache.remove("item:" + itemId); }

    public void reloadChunk(Long chunkId) {
        BotKnowledgeChunk c = chunkMapper.selectById(chunkId);
        if (c != null && "APPROVED".equals(c.getStatus()) && c.getEmbedding() != null) {
            float[] vec = VectorUtil.fromJson(c.getEmbedding());
            if (vec.length > 0) vectorCache.put("chunk:" + chunkId, vec);
            else vectorCache.remove("chunk:" + chunkId);
        } else vectorCache.remove("chunk:" + chunkId);
    }

    public void removeChunk(Long chunkId) { vectorCache.remove("chunk:" + chunkId); }

    /** Unified hybrid search across both FAQ items and document chunks */
    public List<SearchResult> search(String queryText) {
        List<SearchResult> results = new ArrayList<>();
        float[] queryVec = embeddingService.embed(queryText);
        String lower = queryText.toLowerCase();

        // 1. FAQ items
        List<BotKnowledgeItem> items = itemMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeItem>().eq(BotKnowledgeItem::getStatus, 1));
        for (BotKnowledgeItem item : items) {
            double kwScore = itemKeywordScore(lower, item);
            double vecScore = 0;
            if (queryVec.length > 0) {
                float[] itemVec = vectorCache.get("item:" + item.getId());
                if (itemVec != null) vecScore = VectorUtil.cosineSimilarity(queryVec, itemVec);
            }
            double combined = vecScore * 0.7 + kwScore * 0.3;
            if (combined > 0) results.add(new SearchResult(item, combined, kwScore, vecScore));
        }

        // 2. Document chunks (only APPROVED)
        List<BotKnowledgeChunk> chunks = chunkMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeChunk>().eq(BotKnowledgeChunk::getStatus, "APPROVED"));
        for (BotKnowledgeChunk chunk : chunks) {
            float[] chunkVec = vectorCache.get("chunk:" + chunk.getId());
            double vecScore = 0;
            if (queryVec.length > 0 && chunkVec != null) {
                vecScore = VectorUtil.cosineSimilarity(queryVec, chunkVec);
            }
            double kwScore = chunkKeywordScore(lower, KnowledgeTextUtil.chunkEmbeddingText(
                chunk.getSectionPath(), chunk.getContent()));
            double combined = (chunkVec != null && chunkVec.length > 0 && queryVec.length > 0)
                ? vecScore * 0.7 + kwScore * 0.3
                : kwScore;
            if (combined > 0.05) results.add(new SearchResult(chunk, combined, kwScore, vecScore));
        }

        results.sort((a, b) -> Double.compare(b.combinedScore, a.combinedScore));
        return results;
    }

    private double itemKeywordScore(String query, BotKnowledgeItem item) {
        double score = 0;
        String kw = item.getKeywords();
        if (kw != null && !kw.isEmpty()) {
            int hits = 0, total = 0;
            for (String k : kw.split(",")) { total++; if (query.contains(k.trim().toLowerCase())) hits++; }
            if (total > 0 && hits > 0) score = Math.max(score, (double) hits / total * 0.9);
        }
        String q = item.getQuestion();
        if (q != null) {
            q = q.toLowerCase(); int overlap = 0;
            for (int i = 0; i < Math.min(q.length(), 5); i++)
                if (query.contains(String.valueOf(q.charAt(i)))) overlap++;
            score = Math.max(score, (double) overlap / 5 * 0.5);
        }
        return Math.min(score, 1.0);
    }

    private double chunkKeywordScore(String query, String content) {
        if (content == null) return 0;
        int hits = 0, total = 0;
        for (int i = 0; i < query.length() - 1; i++) {
            String bigram = query.substring(i, i + 2);
            total++;
            if (content.contains(bigram)) hits++;
        }
        return total > 0 ? (double) hits / total * 0.6 : 0;
    }

    public static class SearchResult {
        public BotKnowledgeItem item;
        public BotKnowledgeChunk chunk;
        public double combinedScore;
        public double keywordScore;
        public double vectorScore;
        public String type; // "item" or "chunk"

        public SearchResult(BotKnowledgeItem item, double combined, double kw, double vec) {
            this.item = item; this.combinedScore = combined; this.keywordScore = kw;
            this.vectorScore = vec; this.type = "item";
        }
        public SearchResult(BotKnowledgeChunk chunk, double combined, double kw, double vec) {
            this.chunk = chunk; this.combinedScore = combined; this.keywordScore = kw;
            this.vectorScore = vec; this.type = "chunk";
        }
    }
}
