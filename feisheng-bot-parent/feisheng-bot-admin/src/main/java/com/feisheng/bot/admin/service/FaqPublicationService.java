package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.feisheng.bot.admin.entity.BotFaqDraft;
import com.feisheng.bot.admin.entity.BotKnowledgeItem;
import com.feisheng.bot.admin.entity.BotKnowledgeItemChunk;
import com.feisheng.bot.admin.entity.BotQuestionClusterItem;
import com.feisheng.bot.admin.entity.BotUnmatchedQuestion;
import com.feisheng.bot.admin.mapper.BotKnowledgeItemChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeItemMapper;
import com.feisheng.bot.admin.mapper.BotQuestionClusterItemMapper;
import com.feisheng.bot.admin.mapper.BotUnmatchedQuestionMapper;
import com.feisheng.bot.admin.util.VectorUtil;
import com.feisheng.bot.common.util.EmbeddingMetadataUtil;
import com.feisheng.bot.common.util.KnowledgeTextUtil;
import com.feisheng.bot.knowledge.service.KnowledgeIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
public class FaqPublicationService {
    private static final Logger log = LoggerFactory.getLogger(FaqPublicationService.class);

    private final BotKnowledgeItemMapper knowledgeItemMapper;
    private final BotKnowledgeItemChunkMapper itemChunkMapper;
    private final BotQuestionClusterItemMapper clusterItemMapper;
    private final BotUnmatchedQuestionMapper unmatchedQuestionMapper;
    private final EmbeddingService embeddingService;
    private final VectorSearchService vectorSearchService;
    private final KnowledgeIndexService indexService;

    public FaqPublicationService(BotKnowledgeItemMapper knowledgeItemMapper,
                                 BotKnowledgeItemChunkMapper itemChunkMapper,
                                 BotQuestionClusterItemMapper clusterItemMapper,
                                 BotUnmatchedQuestionMapper unmatchedQuestionMapper,
                                 EmbeddingService embeddingService,
                                 VectorSearchService vectorSearchService,
                                 KnowledgeIndexService indexService) {
        this.knowledgeItemMapper = knowledgeItemMapper;
        this.itemChunkMapper = itemChunkMapper;
        this.clusterItemMapper = clusterItemMapper;
        this.unmatchedQuestionMapper = unmatchedQuestionMapper;
        this.embeddingService = embeddingService;
        this.vectorSearchService = vectorSearchService;
        this.indexService = indexService;
    }

    public Long publish(BotFaqDraft draft) {
        if (knowledgeItemMapper.selectCount(new QueryWrapper<BotKnowledgeItem>()
                .eq("question", draft.getQuestion())) > 0) {
            throw new FaqDraftService.FaqDraftException(409, "相同问题已存在，请编辑已有FAQ");
        }

        String alternateQuestions = KnowledgeTextUtil.questionAliasesJson(
            draft.getSimilarQuestionsJson(), draft.getQuestion());
        List<KnowledgeTextUtil.FaqEmbeddingPart> parts = KnowledgeTextUtil.faqEmbeddingParts(
            draft.getQuestion(), draft.getKeywords(), draft.getAnswer(), alternateQuestions);
        List<float[]> embeddings = embeddingService.embedBatch(
            parts.stream().map(KnowledgeTextUtil.FaqEmbeddingPart::embeddingText).toList());
        if (embeddings.size() < parts.size()
                || embeddings.stream().limit(parts.size())
                    .anyMatch(vector -> vector == null || vector.length == 0)) {
            throw new FaqDraftService.FaqDraftException(
                503, "向量生成失败，草稿没有发布，请检查Embedding模型后重试");
        }

        BotKnowledgeItem item = new BotKnowledgeItem();
        item.setCategoryId(0L);
        item.setQuestion(draft.getQuestion());
        item.setAnswer(draft.getAnswer());
        item.setKeywords(blankToNull(draft.getKeywords()));
        item.setAlternateQuestions(alternateQuestions);
        item.setStatus(1);
        item.setHitCount(0);
        item.setDirectAnswerEnabled(0);
        float[] parentVector = embeddings.get(0);
        item.setEmbedding(VectorUtil.toJson(parentVector));
        applyMetadata(item, parts.get(0).embeddingText(), parentVector.length);
        if (knowledgeItemMapper.insert(item) != 1 || item.getId() == null) {
            throw new FaqDraftService.FaqDraftException(500, "FAQ发布失败");
        }

        for (int index = 1; index < parts.size(); index++) {
            KnowledgeTextUtil.FaqEmbeddingPart part = parts.get(index);
            float[] vector = embeddings.get(index);
            BotKnowledgeItemChunk chunk = new BotKnowledgeItemChunk();
            chunk.setItemId(item.getId());
            chunk.setChunkIndex(part.index());
            chunk.setContent(part.answerPart());
            chunk.setEmbedding(VectorUtil.toJson(vector));
            applyMetadata(chunk, part.embeddingText(), vector.length);
            itemChunkMapper.insert(chunk);
        }

        List<Long> questionIds = clusterItemMapper.selectList(
                new QueryWrapper<BotQuestionClusterItem>()
                    .eq("cluster_id", draft.getClusterId()))
            .stream().map(BotQuestionClusterItem::getUnmatchedQuestionId)
            .filter(java.util.Objects::nonNull).distinct().toList();
        if (!questionIds.isEmpty()) {
            unmatchedQuestionMapper.update(null, new UpdateWrapper<BotUnmatchedQuestion>()
                .in("id", questionIds)
                .set("is_resolved", 1));
        }

        refreshIndexesAfterCommit(item.getId());
        return item.getId();
    }

    private void refreshIndexesAfterCommit(Long itemId) {
        Runnable refresh = () -> {
            try {
                vectorSearchService.reloadItem(itemId);
                indexService.sync();
            } catch (Exception error) {
                log.warn("Published FAQ {} but index refresh failed: {}", itemId, error.getMessage());
            }
        };
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            refresh.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                refresh.run();
            }
        });
    }

    private void applyMetadata(BotKnowledgeItem item, String sourceText, int dimensions) {
        EmbeddingService.EmbeddingDescriptor descriptor = embeddingService.descriptor();
        if (descriptor != null) {
            item.setEmbeddingModel(descriptor.model());
            item.setEmbeddingVersion(descriptor.version());
        }
        item.setEmbeddingDimensions(dimensions);
        item.setEmbeddingContentHash(EmbeddingMetadataUtil.contentHash(sourceText));
    }

    private void applyMetadata(BotKnowledgeItemChunk chunk, String sourceText, int dimensions) {
        EmbeddingService.EmbeddingDescriptor descriptor = embeddingService.descriptor();
        if (descriptor != null) {
            chunk.setEmbeddingModel(descriptor.model());
            chunk.setEmbeddingVersion(descriptor.version());
        }
        chunk.setEmbeddingDimensions(dimensions);
        chunk.setEmbeddingContentHash(EmbeddingMetadataUtil.contentHash(sourceText));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
