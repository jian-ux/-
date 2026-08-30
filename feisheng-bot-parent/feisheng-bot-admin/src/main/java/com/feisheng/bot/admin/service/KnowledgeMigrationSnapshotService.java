package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.entity.BotKnowledgeMigrationJob;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeMigrationJobMapper;
import com.feisheng.bot.common.util.EmbeddingMetadataUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/** Creates immutable source snapshots used by the asynchronous migration worker. */
@Service
public class KnowledgeMigrationSnapshotService {
    private static final int COMPLETED = 2;

    private final BotKnowledgeDocumentMapper documentMapper;
    private final BotKnowledgeChunkMapper chunkMapper;
    private final BotKnowledgeMigrationJobMapper jobMapper;
    private final KnowledgeDocumentReleaseService releaseService;

    public KnowledgeMigrationSnapshotService(BotKnowledgeDocumentMapper documentMapper,
                                             BotKnowledgeChunkMapper chunkMapper,
                                             BotKnowledgeMigrationJobMapper jobMapper,
                                             KnowledgeDocumentReleaseService releaseService) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.jobMapper = jobMapper;
        this.releaseService = releaseService;
    }

    @Transactional
    public SnapshotResult create(Long sourceDocumentId, Long operatorId) {
        BotKnowledgeDocument source = requireSource(sourceDocumentId);
        List<BotKnowledgeChunk> sourceChunks = sourceChunks(sourceDocumentId);
        if (sourceChunks.isEmpty()) throw new SnapshotException(409, "源文档没有可迁移切片");
        String sourceHash = sourceHash(sourceChunks);
        String key = requireKnowledgeSetKey(source);
        BotKnowledgeMigrationJob existing = jobMapper.selectOne(new LambdaQueryWrapper<BotKnowledgeMigrationJob>()
            .eq(BotKnowledgeMigrationJob::getSourceDocumentId, sourceDocumentId)
            .eq(BotKnowledgeMigrationJob::getSourceContentHash, sourceHash)
            .in(BotKnowledgeMigrationJob::getStatus, List.of("PENDING", "RUNNING", "PAUSED", "COMPLETED"))
            .last("LIMIT 1"));
        if (existing != null) {
            return result(existing, sourceHash, sourceChunks.size());
        }
        int targetVersion = releaseService.nextVersion(key);

        BotKnowledgeDocument target = copyDocument(source, key, targetVersion);
        documentMapper.insert(target);
        cloneChunks(target.getId(), sourceChunks);

        BotKnowledgeMigrationJob job = new BotKnowledgeMigrationJob();
        job.setSourceDocumentId(sourceDocumentId);
        job.setSourceVersionId(source.getDocumentVersion() == null ? null : source.getDocumentVersion().longValue());
        job.setTargetDocumentId(target.getId());
        job.setTargetVersionId((long) targetVersion);
        job.setKnowledgeSetKey(key);
        job.setSourceContentHash(sourceHash);
        job.setStatus("PENDING");
        job.setCurrentStep("SNAPSHOT");
        job.setTotalUnits(sourceChunks.size());
        job.setProcessedUnits(0);
        job.setConflictUnits(0);
        job.setApprovedUnits(0);
        job.setRetryCount(0);
        job.setMaxRetries(3);
        job.setLockVersion(0L);
        job.setReviewerId(operatorId);
        jobMapper.insert(job);
        return result(job, sourceHash, sourceChunks.size());
    }

    public SnapshotResult cloneTarget(Long jobId) {
        BotKnowledgeMigrationJob job = jobMapper.selectById(jobId);
        if (job == null) throw new SnapshotException(404, "迁移任务不存在");
        BotKnowledgeDocument source = requireSource(job.getSourceDocumentId());
        List<BotKnowledgeChunk> sourceChunks = sourceChunks(source.getId());
        String currentHash = sourceHash(sourceChunks);
        if (!Objects.equals(currentHash, job.getSourceContentHash())) {
            job.setStatus("STALE");
            job.setErrorMessage("源文档内容已变化，快照失效");
            jobMapper.updateById(job);
            throw new SnapshotException(409, "源文档内容已变化，快照失效");
        }
        BotKnowledgeDocument target = job.getTargetDocumentId() == null
            ? null : documentMapper.selectById(job.getTargetDocumentId());
        if (target == null) {
            target = copyDocument(source, requireKnowledgeSetKey(source), job.getTargetVersionId().intValue());
            documentMapper.insert(target);
            job.setTargetDocumentId(target.getId());
            jobMapper.updateById(job);
        }
        List<BotKnowledgeChunk> existing = chunkMapper.selectList(new LambdaQueryWrapper<BotKnowledgeChunk>()
            .eq(BotKnowledgeChunk::getDocumentId, target.getId()));
        reconcileChunks(target.getId(), sourceChunks, existing);
        return result(job, currentHash, sourceChunks.size());
    }

    private BotKnowledgeDocument requireSource(Long id) {
        BotKnowledgeDocument source = documentMapper.selectById(id);
        if (source == null) throw new SnapshotException(404, "源文档不存在");
        if (!Objects.equals(source.getStatus(), COMPLETED)) {
            throw new SnapshotException(409, "源文档尚未处理完成");
        }
        if (!KnowledgeDocumentReleaseService.PUBLISHED.equals(source.getPublishStatus())) {
            throw new SnapshotException(409, "源文档尚未发布");
        }
        if (!"KNOWLEDGE".equalsIgnoreCase(source.getSourceScope())) {
            throw new SnapshotException(409, "仅支持知识库文档迁移");
        }
        return source;
    }

    private List<BotKnowledgeChunk> sourceChunks(Long documentId) {
        List<BotKnowledgeChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<BotKnowledgeChunk>()
            .eq(BotKnowledgeChunk::getDocumentId, documentId)
            .orderByAsc(BotKnowledgeChunk::getChunkIndex)
            .orderByAsc(BotKnowledgeChunk::getId));
        return chunks == null ? List.of() : chunks;
    }

    private BotKnowledgeDocument copyDocument(BotKnowledgeDocument source, String key, int version) {
        BotKnowledgeDocument target = new BotKnowledgeDocument();
        target.setTitle(source.getTitle());
        target.setFileName(source.getFileName());
        target.setFilePath(source.getFilePath());
        target.setBucketName(source.getBucketName());
        target.setObjectKey(source.getObjectKey());
        target.setFileType(source.getFileType());
        target.setMediaType(source.getMediaType());
        target.setSourceScope(source.getSourceScope());
        target.setOcrStatus(source.getOcrStatus());
        target.setOcrText(source.getOcrText());
        target.setOcrLanguage(source.getOcrLanguage());
        target.setOcrError(source.getOcrError());
        target.setExpiresAt(source.getExpiresAt());
        target.setFileSize(source.getFileSize());
        target.setCategoryId(source.getCategoryId());
        target.setStatus(COMPLETED);
        target.setKnowledgeSetKey(key);
        target.setDocumentVersion(version);
        target.setPriority(source.getPriority() == null ? 0 : source.getPriority());
        target.setPublishStatus(KnowledgeDocumentReleaseService.DRAFT);
        target.setEffectiveFrom(null);
        target.setEffectiveTo(null);
        target.setSupersedesDocumentId(source.getId());
        target.setPublishedAt(null);
        target.setQualityStatus(source.getQualityStatus());
        target.setQualityMessage(source.getQualityMessage());
        target.setSourceRowCount(source.getSourceRowCount());
        target.setDetectedQaCount(source.getDetectedQaCount());
        target.setInvalidRowCount(source.getInvalidRowCount());
        return target;
    }

    private void cloneChunks(Long targetId, List<BotKnowledgeChunk> sourceChunks) {
        reconcileChunks(targetId, sourceChunks, List.of());
    }

    private void reconcileChunks(Long targetId, List<BotKnowledgeChunk> sourceChunks, List<BotKnowledgeChunk> existing) {
        List<BotKnowledgeChunk> remaining = new ArrayList<>(existing);
        int nullIndexPosition = 0;
        for (BotKnowledgeChunk source : sourceChunks) {
            int match = -1;
            for (int i = 0; i < remaining.size(); i++) {
                BotKnowledgeChunk candidate = remaining.get(i);
                boolean samePosition = source.getChunkIndex() != null
                    ? Objects.equals(candidate.getChunkIndex(), source.getChunkIndex())
                    : candidate.getChunkIndex() == null && i == nullIndexPosition;
                if (samePosition) {
                    match = i;
                    break;
                }
            }
            if (source.getChunkIndex() == null) nullIndexPosition++;
            if (match >= 0) {
                BotKnowledgeChunk candidate = remaining.remove(match);
                if (Objects.equals(candidate.getContent(), source.getContent())
                    && Objects.equals(candidate.getSectionPath(), source.getSectionPath())) continue;
                chunkMapper.deleteById(candidate.getId());
            }
            BotKnowledgeChunk target = new BotKnowledgeChunk();
            target.setDocumentId(targetId);
            target.setChunkIndex(source.getChunkIndex());
            target.setContent(source.getContent());
            target.setSectionPath(source.getSectionPath());
            target.setCharCount(source.getCharCount());
            target.setChunkStrategyVersion(source.getChunkStrategyVersion());
            target.setContentType(source.getContentType());
            target.setQaQuestion(source.getQaQuestion());
            target.setQaAnswer(source.getQaAnswer());
            target.setQaKey(source.getQaKey());
            target.setQaGroupKey(source.getQaGroupKey());
            target.setQaVersion(source.getQaVersion());
            target.setDirectAnswerEnabled(source.getDirectAnswerEnabled());
            target.setEmbedding(source.getEmbedding());
            target.setEmbeddingModel(source.getEmbeddingModel());
            target.setEmbeddingVersion(source.getEmbeddingVersion());
            target.setEmbeddingDimensions(source.getEmbeddingDimensions());
            target.setEmbeddingContentHash(source.getEmbeddingContentHash());
            target.setStatus(source.getStatus());
            chunkMapper.insert(target);
        }
        for (BotKnowledgeChunk stale : remaining) chunkMapper.deleteById(stale.getId());
    }

    private static String sourceHash(List<BotKnowledgeChunk> chunks) {
        StringBuilder source = new StringBuilder();
        for (BotKnowledgeChunk chunk : chunks) {
            source.append(chunk.getId()).append('\0')
                .append(Objects.toString(chunk.getChunkIndex(), "")).append('\0')
                .append(chunk.getContent()).append('\1');
        }
        return EmbeddingMetadataUtil.contentHash(source.toString());
    }

    private static String requireKnowledgeSetKey(BotKnowledgeDocument source) {
        String key = source.getKnowledgeSetKey();
        if (key == null || key.isBlank()) {
            key = KnowledgeDocumentReleaseService.normalizeKnowledgeSetKey(
                null, source.getFileName(), source.getTitle(), source.getId());
        }
        return key;
    }

    private SnapshotResult result(BotKnowledgeMigrationJob job, String hash, int chunks) {
        return new SnapshotResult(job.getId(), job.getSourceDocumentId(), job.getTargetDocumentId(),
            job.getTargetVersionId() == null ? null : job.getTargetVersionId().intValue(), hash, chunks);
    }

    public record SnapshotResult(Long jobId, Long sourceDocumentId, Long targetDocumentId,
                                 Integer targetVersion, String sourceContentHash, int chunkCount) {}

    public static class SnapshotException extends RuntimeException {
        private final int status;
        public SnapshotException(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }
}
