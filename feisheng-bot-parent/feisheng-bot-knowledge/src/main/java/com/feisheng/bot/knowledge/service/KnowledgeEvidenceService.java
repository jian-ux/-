package com.feisheng.bot.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.knowledge.entity.BotKnowledgeChunk;
import com.feisheng.bot.knowledge.entity.BotKnowledgeDocument;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeDocumentMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves semantic-unit evidence to reviewed original chunks. */
@Service
public class KnowledgeEvidenceService {
    public static final int MAX_EVIDENCE_CHUNKS = 50;

    private final BotKnowledgeChunkMapper chunkMapper;
    private final BotKnowledgeDocumentMapper documentMapper;

    public KnowledgeEvidenceService(BotKnowledgeChunkMapper chunkMapper,
                                    BotKnowledgeDocumentMapper documentMapper) {
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
    }

    public List<Map<String, Object>> findApprovedChunks(List<Long> requestedChunkIds,
                                                        Map<String, Object> filters) {
        List<Long> chunkIds = normalizeIds(requestedChunkIds);
        if (chunkIds.isEmpty()) return Collections.emptyList();
        Map<String, Object> normalizedFilters = PayloadFilters.normalize(filters);
        if (PayloadFilters.isUnsatisfiable(normalizedFilters)) return Collections.emptyList();

        List<BotKnowledgeChunk> chunks = chunkMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeChunk>()
                .in(BotKnowledgeChunk::getId, chunkIds)
                .eq(BotKnowledgeChunk::getStatus, "APPROVED")
                .eq(BotKnowledgeChunk::getDeleted, 0));
        Set<Long> documentIds = new LinkedHashSet<>();
        for (BotKnowledgeChunk chunk : chunks) {
            if (chunk.getDocumentId() != null) documentIds.add(chunk.getDocumentId());
        }
        if (documentIds.isEmpty()) return Collections.emptyList();

        List<BotKnowledgeDocument> documents = documentMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeDocument>()
                .in(BotKnowledgeDocument::getId, documentIds)
                .eq(BotKnowledgeDocument::getStatus, 2)
                .eq(BotKnowledgeDocument::getDeleted, 0));
        Map<Long, BotKnowledgeDocument> documentsById = new HashMap<>();
        for (BotKnowledgeDocument document : documents) {
            if (document.getId() != null && !Integer.valueOf(1).equals(document.getDeleted())
                    && Integer.valueOf(2).equals(document.getStatus())) {
                documentsById.put(document.getId(), document);
            }
        }
        Map<Long, BotKnowledgeChunk> chunksById = new HashMap<>();
        for (BotKnowledgeChunk chunk : chunks) {
            if ("APPROVED".equals(chunk.getStatus())
                    && !Integer.valueOf(1).equals(chunk.getDeleted())
                    && documentsById.containsKey(chunk.getDocumentId())) {
                chunksById.put(chunk.getId(), chunk);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Long chunkId : chunkIds) {
            BotKnowledgeChunk chunk = chunksById.get(chunkId);
            if (chunk == null) continue;
            Map<String, Object> payload = payload(chunk, documentsById.get(chunk.getDocumentId()));
            if (PayloadFilters.matches(payload, normalizedFilters)) result.add(payload);
        }
        return result;
    }

    private List<Long> normalizeIds(List<Long> requestedChunkIds) {
        if (requestedChunkIds == null || requestedChunkIds.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Long id : requestedChunkIds) {
            if (id != null && id > 0) ids.add(id);
            if (ids.size() == MAX_EVIDENCE_CHUNKS) break;
        }
        return List.copyOf(ids);
    }

    private Map<String, Object> payload(BotKnowledgeChunk chunk,
                                        BotKnowledgeDocument document) {
        String mediaType = firstNonBlank(document.getMediaType(), "DOCUMENT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "chunk");
        payload.put("sourceType", "IMAGE".equals(mediaType) ? "image" : "document");
        payload.put("sourceId", chunk.getId());
        payload.put("chunkId", chunk.getId());
        payload.put("documentId", chunk.getDocumentId());
        payload.put("chunkIndex", chunk.getChunkIndex());
        payload.put("title", firstNonBlank(document.getTitle(), document.getFileName(),
            "\u6587\u6863 " + document.getId()));
        payload.put("mediaType", mediaType);
        payload.put("categoryId", document.getCategoryId() == null ? 0L : document.getCategoryId());
        payload.put("sourceScope", nullToEmpty(document.getSourceScope()));
        payload.put("expiresAt", document.getExpiresAt() == null
            ? "" : document.getExpiresAt().toInstant().toString());
        payload.put("sectionPath", nullToEmpty(chunk.getSectionPath()));
        payload.put("content", nullToEmpty(chunk.getContent()));
        payload.put("status", chunk.getStatus());
        if (chunk.getCharCount() != null) payload.put("charCount", chunk.getCharCount());
        if (chunk.getChunkStrategyVersion() != null
                && !chunk.getChunkStrategyVersion().isBlank()) {
            payload.put("chunkStrategyVersion", chunk.getChunkStrategyVersion());
        }
        if ("IMAGE".equals(mediaType)) {
            payload.put("previewUrl", "/api/admin/doc/" + document.getId() + "/preview");
        }
        return payload;
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
}
