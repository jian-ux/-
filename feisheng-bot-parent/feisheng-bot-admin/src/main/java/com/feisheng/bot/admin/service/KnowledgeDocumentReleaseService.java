package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.knowledge.service.KnowledgeIndexService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;

@Service
public class KnowledgeDocumentReleaseService {
    public static final String DRAFT = "DRAFT";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String ARCHIVED = "ARCHIVED";

    private final BotKnowledgeDocumentMapper documentMapper;
    private final BotKnowledgeChunkMapper chunkMapper;
    private final KnowledgeIndexService indexService;

    public KnowledgeDocumentReleaseService(BotKnowledgeDocumentMapper documentMapper,
                                           BotKnowledgeChunkMapper chunkMapper,
                                           KnowledgeIndexService indexService) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.indexService = indexService;
    }

    @Transactional
    public ReleaseResult publish(Long documentId) {
        BotKnowledgeDocument document = requireDocument(documentId);
        if (!Objects.equals(document.getStatus(), 2)) {
            throw new ReleaseException(409, "文档尚未处理完成，不能发布");
        }
        List<BotKnowledgeChunk> chunks = chunks(documentId);
        if (chunks.isEmpty()) throw new ReleaseException(409, "文档没有可发布的切片");
        if (chunks.stream().anyMatch(chunk -> !"APPROVED".equals(chunk.getStatus()))) {
            throw new ReleaseException(409, "全部切片审核通过后才能发布");
        }
        if (chunks.stream().anyMatch(chunk -> chunk.getEmbedding() == null
                || chunk.getEmbedding().isBlank())) {
            throw new ReleaseException(409, "全部切片生成向量后才能发布");
        }

        String knowledgeSetKey = normalizeKnowledgeSetKey(
            document.getKnowledgeSetKey(), document.getFileName(), document.getTitle(), documentId);
        int version = document.getDocumentVersion() == null || document.getDocumentVersion() < 1
            ? nextVersion(knowledgeSetKey) : document.getDocumentVersion();
        Date now = new Date();
        List<BotKnowledgeDocument> currentVersions = documentMapper.selectList(
            new LambdaQueryWrapper<BotKnowledgeDocument>()
                .eq(BotKnowledgeDocument::getKnowledgeSetKey, knowledgeSetKey)
                .eq(BotKnowledgeDocument::getPublishStatus, PUBLISHED)
                .ne(BotKnowledgeDocument::getId, documentId));
        Long supersededId = currentVersions.stream()
            .max(Comparator.comparingInt(value -> value.getDocumentVersion() == null
                ? 1 : value.getDocumentVersion()))
            .map(BotKnowledgeDocument::getId)
            .orElse(null);

        for (BotKnowledgeDocument currentVersion : currentVersions) {
            currentVersion.setPublishStatus(ARCHIVED);
            currentVersion.setEffectiveTo(now);
            documentMapper.updateById(currentVersion);
        }

        document.setKnowledgeSetKey(knowledgeSetKey);
        document.setDocumentVersion(version);
        document.setPriority(document.getPriority() == null ? 0 : document.getPriority());
        document.setPublishStatus(PUBLISHED);
        document.setEffectiveFrom(document.getEffectiveFrom() == null ? now : document.getEffectiveFrom());
        document.setEffectiveTo(null);
        document.setSupersedesDocumentId(supersededId);
        document.setPublishedAt(now);
        documentMapper.updateById(document);
        syncIndexAfterCommit();
        return new ReleaseResult(documentId, knowledgeSetKey, version, PUBLISHED, supersededId);
    }

    @Transactional
    public ReleaseResult archive(Long documentId) {
        BotKnowledgeDocument document = requireDocument(documentId);
        Date now = new Date();
        document.setPublishStatus(ARCHIVED);
        document.setEffectiveTo(now);
        documentMapper.updateById(document);
        syncIndexAfterCommit();
        return new ReleaseResult(documentId, document.getKnowledgeSetKey(),
            document.getDocumentVersion() == null ? 1 : document.getDocumentVersion(),
            ARCHIVED, document.getSupersedesDocumentId());
    }

    @Transactional
    public ReleaseResult updatePriority(Long documentId, Integer priority) {
        if (priority == null || priority < -100 || priority > 100) {
            throw new ReleaseException(400, "优先级必须在 -100 到 100 之间");
        }
        BotKnowledgeDocument document = requireDocument(documentId);
        document.setPriority(priority);
        documentMapper.updateById(document);
        if (PUBLISHED.equals(document.getPublishStatus())) syncIndexAfterCommit();
        return new ReleaseResult(documentId, document.getKnowledgeSetKey(),
            document.getDocumentVersion() == null ? 1 : document.getDocumentVersion(),
            document.getPublishStatus(), document.getSupersedesDocumentId());
    }

    public int nextVersion(String knowledgeSetKey) {
        return documentMapper.selectList(new LambdaQueryWrapper<BotKnowledgeDocument>()
                .eq(BotKnowledgeDocument::getKnowledgeSetKey, knowledgeSetKey))
            .stream()
            .map(BotKnowledgeDocument::getDocumentVersion)
            .filter(Objects::nonNull)
            .max(Integer::compareTo)
            .orElse(0) + 1;
    }

    public static String normalizeKnowledgeSetKey(String configured, String fileName,
                                                   String title, Long documentId) {
        String value = firstText(configured, fileName, title, "document-" + documentId).trim();
        int extension = value.lastIndexOf('.');
        if (extension > 0) value = value.substring(0, extension);
        value = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isBlank()) value = "document-" + documentId;
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    private BotKnowledgeDocument requireDocument(Long documentId) {
        BotKnowledgeDocument document = documentMapper.selectById(documentId);
        if (document == null) throw new ReleaseException(404, "文档不存在");
        return document;
    }

    private List<BotKnowledgeChunk> chunks(Long documentId) {
        return chunkMapper.selectList(new LambdaQueryWrapper<BotKnowledgeChunk>()
            .eq(BotKnowledgeChunk::getDocumentId, documentId));
    }

    private void syncIndexAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            indexService.sync();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                indexService.sync();
            }
        });
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "document";
    }

    public record ReleaseResult(Long documentId, String knowledgeSetKey, int documentVersion,
                                String publishStatus, Long supersededDocumentId) {}

    public static class ReleaseException extends RuntimeException {
        private final int status;

        public ReleaseException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
