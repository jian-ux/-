package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeItem;
import com.feisheng.bot.admin.entity.BotKnowledgeItemChunk;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeItemChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeItemMapper;
import com.feisheng.bot.admin.util.VectorUtil;
import com.feisheng.bot.common.util.EmbeddingMetadataUtil;
import com.feisheng.bot.common.util.KnowledgeTextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeEmbeddingBackfillService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeEmbeddingBackfillService.class);
    private static final int BATCH_SIZE = 20;

    private final BotKnowledgeItemMapper itemMapper;
    private final BotKnowledgeChunkMapper chunkMapper;
    private final BotKnowledgeItemChunkMapper itemChunkMapper;
    private final EmbeddingService embeddingService;
    private final VectorSearchService vectorSearchService;

    @Autowired
    public KnowledgeEmbeddingBackfillService(BotKnowledgeItemMapper itemMapper,
                                             BotKnowledgeChunkMapper chunkMapper,
                                             EmbeddingService embeddingService,
                                             VectorSearchService vectorSearchService,
                                             BotKnowledgeItemChunkMapper itemChunkMapper) {
        this.itemMapper = itemMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingService = embeddingService;
        this.vectorSearchService = vectorSearchService;
        this.itemChunkMapper = itemChunkMapper;
    }

    public KnowledgeEmbeddingBackfillService(BotKnowledgeItemMapper itemMapper,
                                             BotKnowledgeChunkMapper chunkMapper,
                                             EmbeddingService embeddingService,
                                             VectorSearchService vectorSearchService) {
        this(itemMapper, chunkMapper, embeddingService, vectorSearchService, null);
    }

    public BackfillStatus status() {
        long faqTotal = itemMapper.selectCount(new LambdaQueryWrapper<BotKnowledgeItem>()
            .eq(BotKnowledgeItem::getStatus, 1));
        long faqPending = itemMapper.selectCount(missingItemEmbeddingQuery());
        long chunkTotal = chunkMapper.selectCount(new LambdaQueryWrapper<BotKnowledgeChunk>()
            .eq(BotKnowledgeChunk::getStatus, "APPROVED"));
        long chunkPending = chunkMapper.selectCount(missingChunkEmbeddingQuery());
        return new BackfillStatus(
            embeddingService.isAvailable(), faqTotal, faqTotal - faqPending, faqPending,
            chunkTotal, chunkTotal - chunkPending, chunkPending);
    }

    public BackfillReport backfillMissing() {
        return backfill(itemMapper.selectList(missingItemEmbeddingQuery()),
            chunkMapper.selectList(missingChunkEmbeddingQuery()));
    }

    /** Re-embeds every active source after an embedding model change. */
    public BackfillReport backfillAll() {
        return backfill(
            itemMapper.selectList(new LambdaQueryWrapper<BotKnowledgeItem>()
                .eq(BotKnowledgeItem::getStatus, 1)),
            chunkMapper.selectList(new LambdaQueryWrapper<BotKnowledgeChunk>()
                .eq(BotKnowledgeChunk::getStatus, "APPROVED")));
    }

    private BackfillReport backfill(List<BotKnowledgeItem> items,
                                    List<BotKnowledgeChunk> chunks) {
        if (!embeddingService.isAvailable()) {
            throw new IllegalStateException("请先配置并启用 Embedding 类型模型");
        }
        MutableReport report = new MutableReport(items.size(), chunks.size());

        for (int start = 0; start < items.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, items.size());
            List<BotKnowledgeItem> batch = items.subList(start, end);
            List<String> texts = batch.stream().map(this::faqEmbeddingText).toList();
            applyItemEmbeddings(batch, embeddingService.embedBatch(texts), report);
        }

        for (int start = 0; start < chunks.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, chunks.size());
            List<BotKnowledgeChunk> batch = chunks.subList(start, end);
            List<String> texts = batch.stream().map(this::chunkEmbeddingText).toList();
            applyChunkEmbeddings(batch, embeddingService.embedBatch(texts), report);
        }

        BackfillReport result = report.toReport();
        log.info("Knowledge embedding backfill completed: total={}, success={}, failed={}",
            result.total(), result.success(), result.failed());
        return result;
    }

    private void applyItemEmbeddings(List<BotKnowledgeItem> items, List<float[]> embeddings,
                                     MutableReport report) {
        for (int i = 0; i < items.size(); i++) {
            BotKnowledgeItem item = items.get(i);
            float[] vector = vectorAt(embeddings, i);
            if (vector.length == 0) {
                report.error("faq", item.getId(), "Embedding API 返回空向量");
                continue;
            }
            try {
                List<KnowledgeTextUtil.FaqEmbeddingPart> parts = KnowledgeTextUtil.faqEmbeddingParts(
                    item.getQuestion(), item.getKeywords(), item.getAnswer(),
                    item.getAlternateQuestions());
                List<float[]> childEmbeddings = parts.size() <= 1
                    ? List.of()
                    : embeddingService.embedBatch(parts.subList(1, parts.size()).stream()
                        .map(KnowledgeTextUtil.FaqEmbeddingPart::embeddingText).toList());
                if (!validEmbeddings(childEmbeddings, parts.size() - 1)) {
                    report.error("faq", item.getId(), "FAQ 子分片 Embedding API 返回空向量");
                    continue;
                }
                item.setEmbedding(VectorUtil.toJson(vector));
                applyEmbeddingMetadata(item, faqEmbeddingText(item), vector.length);
                itemMapper.updateById(item);
                replaceFaqChunks(item.getId(), parts, childEmbeddings);
                vectorSearchService.reloadItem(item.getId());
                report.faqSuccess++;
                report.recordDimensions(vector.length);
            } catch (Exception e) {
                report.error("faq", item.getId(), e.getMessage());
            }
        }
    }

    private void applyChunkEmbeddings(List<BotKnowledgeChunk> chunks, List<float[]> embeddings,
                                      MutableReport report) {
        for (int i = 0; i < chunks.size(); i++) {
            BotKnowledgeChunk chunk = chunks.get(i);
            float[] vector = vectorAt(embeddings, i);
            if (vector.length == 0) {
                report.error("chunk", chunk.getId(), "Embedding API 返回空向量");
                continue;
            }
            try {
                chunk.setEmbedding(VectorUtil.toJson(vector));
                applyEmbeddingMetadata(chunk, chunkEmbeddingText(chunk), vector.length);
                chunkMapper.updateById(chunk);
                vectorSearchService.reloadChunk(chunk.getId());
                report.chunkSuccess++;
                report.recordDimensions(vector.length);
            } catch (Exception e) {
                report.error("chunk", chunk.getId(), e.getMessage());
            }
        }
    }

    private LambdaQueryWrapper<BotKnowledgeItem> missingItemEmbeddingQuery() {
        return new LambdaQueryWrapper<BotKnowledgeItem>()
            .eq(BotKnowledgeItem::getStatus, 1)
            .and(q -> q.isNull(BotKnowledgeItem::getEmbedding)
                .or().eq(BotKnowledgeItem::getEmbedding, "")
                .or().eq(BotKnowledgeItem::getEmbedding, "[]"));
    }

    private LambdaQueryWrapper<BotKnowledgeChunk> missingChunkEmbeddingQuery() {
        return new LambdaQueryWrapper<BotKnowledgeChunk>()
            .eq(BotKnowledgeChunk::getStatus, "APPROVED")
            .and(q -> q.isNull(BotKnowledgeChunk::getEmbedding)
                .or().eq(BotKnowledgeChunk::getEmbedding, "")
                .or().eq(BotKnowledgeChunk::getEmbedding, "[]"));
    }

    private String faqEmbeddingText(BotKnowledgeItem item) {
        return KnowledgeTextUtil.faqEmbeddingText(
            item.getQuestion(), item.getKeywords(), item.getAnswer(),
            item.getAlternateQuestions());
    }

    private String chunkEmbeddingText(BotKnowledgeChunk chunk) {
        return KnowledgeTextUtil.chunkEmbeddingText(chunk.getSectionPath(), chunk.getContent());
    }

    private void replaceFaqChunks(Long itemId,
                                  List<KnowledgeTextUtil.FaqEmbeddingPart> parts,
                                  List<float[]> childEmbeddings) {
        if (itemChunkMapper == null) return;
        itemChunkMapper.delete(new LambdaQueryWrapper<BotKnowledgeItemChunk>()
            .eq(BotKnowledgeItemChunk::getItemId, itemId));
        for (int i = 1; i < parts.size(); i++) {
            KnowledgeTextUtil.FaqEmbeddingPart part = parts.get(i);
            float[] embedding = childEmbeddings.get(i - 1);
            BotKnowledgeItemChunk chunk = new BotKnowledgeItemChunk();
            chunk.setItemId(itemId);
            chunk.setChunkIndex(part.index());
            chunk.setContent(part.answerPart());
            chunk.setEmbedding(VectorUtil.toJson(embedding));
            applyEmbeddingMetadata(chunk, part.embeddingText(), embedding.length);
            itemChunkMapper.insert(chunk);
        }
    }

    private boolean validEmbeddings(List<float[]> embeddings, int expected) {
        if (expected == 0) return true;
        if (embeddings == null || embeddings.size() < expected) return false;
        return embeddings.stream().limit(expected)
            .allMatch(value -> value != null && value.length > 0);
    }

    private void applyEmbeddingMetadata(BotKnowledgeItem item, String sourceText, int dimensions) {
        EmbeddingService.EmbeddingDescriptor descriptor = embeddingService.descriptor();
        if (descriptor != null) {
            item.setEmbeddingModel(descriptor.model());
            item.setEmbeddingVersion(descriptor.version());
        }
        item.setEmbeddingDimensions(dimensions);
        item.setEmbeddingContentHash(EmbeddingMetadataUtil.contentHash(sourceText));
    }

    private void applyEmbeddingMetadata(BotKnowledgeChunk chunk, String sourceText, int dimensions) {
        EmbeddingService.EmbeddingDescriptor descriptor = embeddingService.descriptor();
        if (descriptor != null) {
            chunk.setEmbeddingModel(descriptor.model());
            chunk.setEmbeddingVersion(descriptor.version());
        }
        chunk.setEmbeddingDimensions(dimensions);
        chunk.setEmbeddingContentHash(EmbeddingMetadataUtil.contentHash(sourceText));
    }

    private void applyEmbeddingMetadata(BotKnowledgeItemChunk chunk, String sourceText,
                                        int dimensions) {
        EmbeddingService.EmbeddingDescriptor descriptor = embeddingService.descriptor();
        if (descriptor != null) {
            chunk.setEmbeddingModel(descriptor.model());
            chunk.setEmbeddingVersion(descriptor.version());
        }
        chunk.setEmbeddingDimensions(dimensions);
        chunk.setEmbeddingContentHash(EmbeddingMetadataUtil.contentHash(sourceText));
    }

    private float[] vectorAt(List<float[]> embeddings, int index) {
        if (embeddings == null || index >= embeddings.size() || embeddings.get(index) == null) {
            return new float[0];
        }
        return embeddings.get(index);
    }

    public record BackfillStatus(boolean embeddingAvailable,
                                 long faqTotal, long faqEmbedded, long faqPending,
                                 long chunkTotal, long chunkEmbedded, long chunkPending) {}

    public record BackfillError(String sourceType, Long sourceId, String reason) {}

    public record BackfillReport(int total, int success, int failed,
                                 int faqTotal, int faqSuccess,
                                 int chunkTotal, int chunkSuccess,
                                 int dimensions, List<BackfillError> errors) {}

    private static class MutableReport {
        private final int faqTotal;
        private final int chunkTotal;
        private int faqSuccess;
        private int chunkSuccess;
        private int dimensions;
        private final List<BackfillError> errors = new ArrayList<>();

        private MutableReport(int faqTotal, int chunkTotal) {
            this.faqTotal = faqTotal;
            this.chunkTotal = chunkTotal;
        }

        private void recordDimensions(int value) {
            if (dimensions == 0) dimensions = value;
        }

        private void error(String sourceType, Long sourceId, String reason) {
            errors.add(new BackfillError(sourceType, sourceId,
                StringUtils.hasText(reason) ? reason : "向量写入失败"));
        }

        private BackfillReport toReport() {
            int total = faqTotal + chunkTotal;
            int success = faqSuccess + chunkSuccess;
            return new BackfillReport(total, success, total - success,
                faqTotal, faqSuccess, chunkTotal, chunkSuccess, dimensions, List.copyOf(errors));
        }
    }
}
