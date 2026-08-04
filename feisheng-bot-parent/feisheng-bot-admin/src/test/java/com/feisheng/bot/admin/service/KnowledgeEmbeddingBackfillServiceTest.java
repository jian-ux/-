package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotKnowledgeChunk;
import com.feisheng.bot.admin.entity.BotKnowledgeItem;
import com.feisheng.bot.admin.entity.BotKnowledgeItemChunk;
import com.feisheng.bot.admin.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeItemChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeItemMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeEmbeddingBackfillServiceTest {
    @Mock private BotKnowledgeItemMapper itemMapper;
    @Mock private BotKnowledgeChunkMapper chunkMapper;
    @Mock private BotKnowledgeItemChunkMapper itemChunkMapper;
    @Mock private EmbeddingService embeddingService;
    @Mock private VectorSearchService vectorSearchService;

    @Test
    void backfillsFaqItemsAndApprovedDocumentChunks() {
        BotKnowledgeItem item = item(1L, "如何重置密码", "点击忘记密码", "密码,重置");
        BotKnowledgeChunk chunk = chunk(2L, "登录页提供忘记密码入口。", "APPROVED");
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.descriptor()).thenReturn(
            new EmbeddingService.EmbeddingDescriptor("embedding-3", "model-v1"));
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
        when(embeddingService.embedBatch(any())).thenReturn(List.of(new float[]{0.1f, 0.2f}));

        KnowledgeEmbeddingBackfillService service = new KnowledgeEmbeddingBackfillService(
            itemMapper, chunkMapper, embeddingService, vectorSearchService);

        KnowledgeEmbeddingBackfillService.BackfillReport report = service.backfillMissing();

        assertEquals(2, report.total());
        assertEquals(2, report.success());
        assertEquals(0, report.failed());
        assertEquals(1, report.faqSuccess());
        assertEquals(1, report.chunkSuccess());
        assertEquals("model-v1", item.getEmbeddingVersion());
        assertEquals(2, item.getEmbeddingDimensions());
        assertEquals("model-v1", chunk.getEmbeddingVersion());
        verify(itemMapper).updateById(item);
        verify(chunkMapper).updateById(chunk);
        verify(vectorSearchService).reloadItem(1L);
        verify(vectorSearchService).reloadChunk(2L);
    }

    @Test
    void recordsEmptyEmbeddingAsFailureWithoutOverwritingKnowledge() {
        BotKnowledgeItem item = item(3L, "退款流程", "提交退款申请", "退款");
        when(embeddingService.isAvailable()).thenReturn(true);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        when(embeddingService.embedBatch(any())).thenReturn(List.of(new float[0]));

        KnowledgeEmbeddingBackfillService service = new KnowledgeEmbeddingBackfillService(
            itemMapper, chunkMapper, embeddingService, vectorSearchService);

        KnowledgeEmbeddingBackfillService.BackfillReport report = service.backfillMissing();

        assertEquals(1, report.total());
        assertEquals(0, report.success());
        assertEquals(1, report.failed());
        assertEquals(1, report.errors().size());
        verify(itemMapper, never()).updateById(any(BotKnowledgeItem.class));
        verify(vectorSearchService, never()).reloadItem(any());
    }

    @Test
    void backfillsAllLongFaqPartsBeforeReplacingChildVectors() {
        BotKnowledgeItem item = item(4L, "长答案", "内容".repeat(1500) + "尾部词", "说明");
        when(embeddingService.isAvailable()).thenReturn(true);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        when(embeddingService.embedBatch(any())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(value -> new float[]{0.3f, 0.4f}).toList();
        });
        KnowledgeEmbeddingBackfillService service = new KnowledgeEmbeddingBackfillService(
            itemMapper, chunkMapper, embeddingService, vectorSearchService, itemChunkMapper);

        KnowledgeEmbeddingBackfillService.BackfillReport report = service.backfillMissing();

        assertEquals(1, report.success());
        verify(itemChunkMapper, atLeastOnce()).insert(any(BotKnowledgeItemChunk.class));
        verify(itemMapper).updateById(item);
    }

    private BotKnowledgeItem item(Long id, String question, String answer, String keywords) {
        BotKnowledgeItem item = new BotKnowledgeItem();
        item.setId(id);
        item.setQuestion(question);
        item.setAnswer(answer);
        item.setKeywords(keywords);
        item.setStatus(1);
        return item;
    }

    private BotKnowledgeChunk chunk(Long id, String content, String status) {
        BotKnowledgeChunk chunk = new BotKnowledgeChunk();
        chunk.setId(id);
        chunk.setDocumentId(20L);
        chunk.setContent(content);
        chunk.setStatus(status);
        return chunk;
    }
}
