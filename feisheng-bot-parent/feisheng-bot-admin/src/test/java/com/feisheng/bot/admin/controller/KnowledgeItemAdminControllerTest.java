package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.feisheng.bot.admin.entity.BotKnowledgeItem;
import com.feisheng.bot.admin.entity.BotKnowledgeItemChunk;
import com.feisheng.bot.admin.mapper.BotKnowledgeItemChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeItemMapper;
import com.feisheng.bot.admin.service.EmbeddingService;
import com.feisheng.bot.admin.service.VectorSearchService;
import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.knowledge.service.KnowledgeIndexService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeItemAdminControllerTest {
    @Mock private BotKnowledgeItemMapper mapper;
    @Mock private BotKnowledgeItemChunkMapper itemChunkMapper;
    @Mock private EmbeddingService embeddingService;
    @Mock private VectorSearchService vectorSearch;
    @Mock private KnowledgeIndexService indexService;

    private KnowledgeItemAdminController controller;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
            new MybatisConfiguration(), "KnowledgeItemAdminControllerTest");
        assistant.setCurrentNamespace(BotKnowledgeItemMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, BotKnowledgeItem.class);
        controller = new KnowledgeItemAdminController(
            mapper, embeddingService, vectorSearch, indexService, itemChunkMapper);
        controller.init();
    }

    @AfterEach
    void tearDown() {
        controller.shutdown();
    }

    @Test
    void rejectsBlankQuestionBeforeInsert() {
        BotKnowledgeItem item = item("  ", "有效答案", "关键词");

        R<Void> result = controller.add(item);

        assertEquals(400, result.getCode());
        assertEquals("问题不能为空", result.getMsg());
        verify(mapper, never()).insert(any(BotKnowledgeItem.class));
    }

    @Test
    void rejectsBlankAnswerBeforeInsert() {
        BotKnowledgeItem item = item("有效问题", "  ", "关键词");

        R<Void> result = controller.add(item);

        assertEquals(400, result.getCode());
        assertEquals("答案不能为空", result.getMsg());
        verify(mapper, never()).insert(any(BotKnowledgeItem.class));
    }

    @Test
    void rejectsDuplicateQuestion() {
        when(mapper.selectCount(any())).thenReturn(1L);

        R<Void> result = controller.add(item("重复问题", "答案", "关键词"));

        assertEquals(409, result.getCode());
        assertEquals("相同问题已存在", result.getMsg());
        verify(mapper, never()).insert(any(BotKnowledgeItem.class));
    }

    @Test
    void addUsesOnlyNormalizedEditableFields() {
        when(mapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            BotKnowledgeItem value = invocation.getArgument(0);
            value.setId(17L);
            return 1;
        }).when(mapper).insert(any(BotKnowledgeItem.class));
        when(mapper.selectById(17L)).thenReturn(null);

        BotKnowledgeItem submitted = item("  新问题  ", "  新答案  ", "  关键词一,关键词二  ");
        submitted.setId(99L);
        submitted.setEmbedding("client-vector");
        submitted.setHitCount(88);
        submitted.setStatus(0);
        submitted.setDirectAnswerEnabled(1);

        R<Void> result = controller.add(submitted);

        assertEquals(200, result.getCode());
        ArgumentCaptor<BotKnowledgeItem> captor = ArgumentCaptor.forClass(BotKnowledgeItem.class);
        verify(mapper).insert(captor.capture());
        BotKnowledgeItem inserted = captor.getValue();
        assertEquals("新问题", inserted.getQuestion());
        assertEquals("新答案", inserted.getAnswer());
        assertEquals("关键词一,关键词二", inserted.getKeywords());
        assertEquals(0, inserted.getHitCount());
        assertEquals(1, inserted.getStatus());
        assertEquals(1, inserted.getDirectAnswerEnabled());
        assertNull(inserted.getEmbedding());
    }

    @Test
    void updateRejectsMissingRecord() {
        BotKnowledgeItem submitted = item("问题", "答案", "关键词");
        submitted.setId(404L);
        when(mapper.selectById(404L)).thenReturn(null);

        R<Void> result = controller.update(submitted);

        assertEquals(404, result.getCode());
        verify(mapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void updateInvalidatesOldVectorBeforeRegeneration() {
        BotKnowledgeItem submitted = item("更新问题", "更新答案", "更新关键词");
        submitted.setId(16L);
        when(mapper.selectById(16L)).thenReturn(submitted);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        R<Void> result = controller.update(submitted);

        assertEquals(200, result.getCode());
        verify(vectorSearch).removeItem(16L);
        verify(indexService, atLeastOnce()).sync();
    }

    @Test
    void reEmbedPersistsLongFaqTailAsChildVectors() {
        String answer = "开头说明。" + "中间内容".repeat(1200) + "尾部检索词";
        BotKnowledgeItem existing = item("长答案如何检索？", answer, "长答案");
        existing.setId(31L);
        when(mapper.selectById(31L)).thenReturn(existing);
        doAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(value -> new float[]{0.1f, 0.2f}).toList();
        }).when(embeddingService).embedBatch(any());

        R<java.util.Map<String, Object>> result = controller.reEmbed(31L);

        assertEquals(200, result.getCode());
        ArgumentCaptor<BotKnowledgeItemChunk> chunks =
            ArgumentCaptor.forClass(BotKnowledgeItemChunk.class);
        verify(itemChunkMapper, atLeastOnce()).insert(chunks.capture());
        assertTrue(chunks.getAllValues().stream()
            .anyMatch(chunk -> chunk.getContent().contains("尾部检索词")));
        verify(vectorSearch).reloadItem(31L);
        verify(indexService).sync();
    }

    private BotKnowledgeItem item(String question, String answer, String keywords) {
        BotKnowledgeItem item = new BotKnowledgeItem();
        item.setQuestion(question);
        item.setAnswer(answer);
        item.setKeywords(keywords);
        return item;
    }
}
