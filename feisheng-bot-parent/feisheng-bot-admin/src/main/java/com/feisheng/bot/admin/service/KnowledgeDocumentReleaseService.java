package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.knowledge.service.KnowledgeIndexService;
import com.feisheng.bot.knowledge.service.StructuredKnowledgeUnitIndexService;
import com.feisheng.bot.admin.entity.BotKnowledgeMigrationJob;
import com.feisheng.bot.admin.mapper.BotKnowledgeMigrationJobMapper;
import com.feisheng.bot.common.util.EmbeddingMetadataUtil;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final StructuredKnowledgeUnitIndexService structuredIndexService;
    private final BotKnowledgeMigrationJobMapper migrationJobMapper;

    public KnowledgeDocumentReleaseService(BotKnowledgeDocumentMapper documentMapper,
                                           BotKnowledgeChunkMapper chunkMapper,
                                           KnowledgeIndexService indexService) {
        this(documentMapper, chunkMapper, indexService, null, null);
    }

    @Autowired
    public KnowledgeDocumentReleaseService(BotKnowledgeDocumentMapper documentMapper,
                                           BotKnowledgeChunkMapper chunkMapper,
                                           KnowledgeIndexService indexService,
                                           StructuredKnowledgeUnitIndexService structuredIndexService,
                                           BotKnowledgeMigrationJobMapper migrationJobMapper) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.indexService = indexService;
        this.structuredIndexService = structuredIndexService;
        this.migrationJobMapper = migrationJobMapper;
    }

    /** Guarded document-level migration switch. The old version remains published until commit. */
    @Transactional
    public ReleaseResult switchMigration(Long jobId, Long operatorId) {
        requireMigrationDependencies();
        BotKnowledgeMigrationJob job = migrationJobMapper.findByIdForUpdate(jobId);
        if (job == null) throw new ReleaseException(404, "迁移任务不存在");
        if (!"READY_TO_SWITCH".equals(job.getStatus())) throw new ReleaseException(409, "迁移任务尚未通过发布闸门");
        BotKnowledgeDocument source = requireDocument(job.getSourceDocumentId());
        BotKnowledgeDocument target = requireDocument(job.getTargetDocumentId());
        String key = firstText(job.getKnowledgeSetKey(), target.getKnowledgeSetKey());
        if (!Objects.equals(key, target.getKnowledgeSetKey()) || !Objects.equals(key, source.getKnowledgeSetKey())) {
            throw new ReleaseException(409, "源、目标与任务知识集不一致");
        }
        // Database row lock serializes switches across JVMs and transactions.
        List<BotKnowledgeDocument> published = documentMapper.selectPublishedForUpdateByKnowledgeSetKey(key);
        if (published == null) published = List.of();
        String currentHash = sourceContentHash(source.getId());
            if (!Objects.equals(currentHash, job.getSourceContentHash())) {
                throw new ReleaseException(409, "源文档内容已变化，不能切换");
            }
            StructuredKnowledgeUnitIndexService.ShadowIndexHandle handle =
                structuredIndexService.buildShadowIndex(target.getId());
            StructuredKnowledgeUnitIndexService.ShadowValidation validation =
                structuredIndexService.validateShadowIndex(handle);
            if (!validation.success()) throw new ReleaseException(409, "影子索引校验失败: " + validation.smokeFailures());
            if (indexService != null) {
                KnowledgeIndexService.ShadowIndexHandle regular = indexService.buildShadowIndex(target.getId());
                KnowledgeIndexService.ShadowValidation regularValidation = indexService.validateShadowIndex(regular);
                if (!regularValidation.success()) {
                    throw new ReleaseException(409, "普通索引影子校验失败: " + regularValidation.smokeFailures());
                }
            }
            Date now = new Date();
            Long supersededId = source.getId();
            if (documentMapper.publishDraftWithSupersedesGuarded(target.getId(), now, now, supersededId) != 1) {
                throw new ReleaseException(409, "目标版本已被切换或不是草稿");
            }
            for (BotKnowledgeDocument current : published) {
                if (!Objects.equals(current.getId(), target.getId())
                        && documentMapper.archivePublishedGuarded(current.getId(), now) != 1) {
                    throw new ReleaseException(409, "已有版本被其他操作修改");
                }
            }
            target.setPublishStatus(PUBLISHED);
            target.setPublishedAt(now);
            target.setEffectiveFrom(now);
            target.setEffectiveTo(null);
            target.setSupersedesDocumentId(supersededId);
            job.setStatus("COMPLETED");
            job.setCurrentStep("COMPLETED");
            job.setSwitchedAt(now);
            job.setReviewerId(operatorId);
            migrationJobMapper.updateById(job);
            syncIndexAfterCommit();
            return new ReleaseResult(target.getId(), key,
                target.getDocumentVersion() == null ? 1 : target.getDocumentVersion(), PUBLISHED, source.getId());

    }

    @Transactional
    public ReleaseResult rollback(String knowledgeSetKey, Long targetDocumentId, Long operatorId) {
        requireMigrationDependencies();
        String key = normalizeKnowledgeSetKey(knowledgeSetKey, null, null, 0L);
        List<BotKnowledgeDocument> versions = documentMapper.selectPublishedForUpdateByKnowledgeSetKey(key);
            BotKnowledgeDocument current = versions.isEmpty() ? null : versions.get(0);
            BotKnowledgeDocument restored = targetDocumentId == null ? null : requireDocument(targetDocumentId);
            if (restored == null) {
                List<BotKnowledgeDocument> archived = documentMapper.selectList(new LambdaQueryWrapper<BotKnowledgeDocument>()
                    .eq(BotKnowledgeDocument::getKnowledgeSetKey, key)
                    .eq(BotKnowledgeDocument::getPublishStatus, ARCHIVED)
                    .eq(BotKnowledgeDocument::getDeleted, 0)
                    .orderByDesc(BotKnowledgeDocument::getDocumentVersion)
                    .orderByDesc(BotKnowledgeDocument::getId));
                restored = archived.isEmpty() ? null : archived.get(0);
            }
            if (restored == null || !ARCHIVED.equals(restored.getPublishStatus())
                    || !Objects.equals(key, restored.getKnowledgeSetKey())) {
                throw new ReleaseException(409, "没有可回滚的归档版本");
            }
            Date now = new Date();
            if (current != null && documentMapper.archivePublishedGuarded(current.getId(), now) != 1) {
                throw new ReleaseException(409, "当前版本已被其他操作修改");
            }
            if (documentMapper.restoreArchivedGuarded(restored.getId(), now, now,
                    current == null ? null : current.getId()) != 1) {
                throw new ReleaseException(409, "归档版本已被其他操作修改");
            }
            BotKnowledgeMigrationJob audit = migrationJobMapper.selectOne(new LambdaQueryWrapper<BotKnowledgeMigrationJob>()
                .eq(BotKnowledgeMigrationJob::getTargetDocumentId, restored.getId())
                .last("LIMIT 1"));
            if (audit != null) {
                audit.setReviewerId(operatorId);
                audit.setReviewedAt(now);
                audit.setReviewReason("ROLLBACK");
                migrationJobMapper.updateById(audit);
            }
            syncIndexAfterCommit();
            return new ReleaseResult(restored.getId(), key,
                restored.getDocumentVersion() == null ? 1 : restored.getDocumentVersion(), PUBLISHED,
                current == null ? null : current.getId());

    }

    private void requireMigrationDependencies() {
        if (structuredIndexService == null || migrationJobMapper == null) {
            throw new ReleaseException(500, "迁移发布组件未配置");
        }
    }

    private String sourceContentHash(Long documentId) {
        List<BotKnowledgeChunk> sourceChunks = chunkMapper.selectList(new LambdaQueryWrapper<BotKnowledgeChunk>()
            .eq(BotKnowledgeChunk::getDocumentId, documentId)
            .orderByAsc(BotKnowledgeChunk::getChunkIndex).orderByAsc(BotKnowledgeChunk::getId));
        StringBuilder text = new StringBuilder();
        for (BotKnowledgeChunk chunk : sourceChunks) {
            text.append(chunk.getId()).append('\0').append(Objects.toString(chunk.getChunkIndex(), ""))
                .append('\0').append(chunk.getContent()).append('\1');
        }
        return EmbeddingMetadataUtil.contentHash(text.toString());
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
            if (indexService != null) indexService.sync();
            if (structuredIndexService != null) structuredIndexService.sync();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (indexService != null) indexService.sync();
                if (structuredIndexService != null) structuredIndexService.sync();
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
