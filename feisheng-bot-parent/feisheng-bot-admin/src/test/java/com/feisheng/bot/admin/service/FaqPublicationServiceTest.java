package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotFaqDraft;
import com.feisheng.bot.admin.entity.BotKnowledgeItem;
import com.feisheng.bot.admin.entity.BotQuestionClusterItem;
import com.feisheng.bot.admin.mapper.BotKnowledgeItemChunkMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeItemMapper;
import com.feisheng.bot.admin.mapper.BotQuestionClusterItemMapper;
import com.feisheng.bot.admin.mapper.BotUnmatchedQuestionMapper;
import com.feisheng.bot.knowledge.service.KnowledgeIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FaqPublicationServiceTest {
    @Mock private BotKnowledgeItemMapper knowledgeItemMapper;
    @Mock private BotKnowledgeItemChunkMapper itemChunkMapper;
    @Mock private BotQuestionClusterItemMapper clusterItemMapper;
    @Mock private BotUnmatchedQuestionMapper unmatchedQuestionMapper;
    @Mock private EmbeddingService embeddingService;
    @Mock private VectorSearchService vectorSearchService;
    @Mock private KnowledgeIndexService indexService;

    private FaqPublicationService service;

    @BeforeEach
    void setUp() {
        service = new FaqPublicationService(knowledgeItemMapper, itemChunkMapper,
            clusterItemMapper, unmatchedQuestionMapper, embeddingService,
            vectorSearchService, indexService);
    }

    @Test
    void publishesEmbeddedFaqAndResolvesSourceQuestions() {
        when(knowledgeItemMapper.selectCount(any())).thenReturn(0L);
        when(embeddingService.embedBatch(any())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(ignored -> new float[]{0.1f, 0.2f}).toList();
        });
        when(embeddingService.descriptor()).thenReturn(
            new EmbeddingService.EmbeddingDescriptor("embed-a", "v1"));
        doAnswer(invocation -> {
            ((BotKnowledgeItem) invocation.getArgument(0)).setId(70L);
            return 1;
        }).when(knowledgeItemMapper).insert(any(BotKnowledgeItem.class));
        BotQuestionClusterItem source = new BotQuestionClusterItem();
        source.setUnmatchedQuestionId(11L);
        when(clusterItemMapper.selectList(any())).thenReturn(List.of(source));

        Long itemId = service.publish(draft());

        assertThat(itemId).isEqualTo(70L);
        ArgumentCaptor<BotKnowledgeItem> captor = ArgumentCaptor.forClass(BotKnowledgeItem.class);
        verify(knowledgeItemMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        assertThat(captor.getValue().getDirectAnswerEnabled()).isZero();
        assertThat(captor.getValue().getEmbedding()).isNotBlank();
        assertThat(captor.getValue().getAlternateQuestions())
            .isEqualTo("[\"电子合同多少钱一份？\"]");
        verify(unmatchedQuestionMapper).update(any(), any());
        verify(vectorSearchService).reloadItem(70L);
        verify(indexService).sync();
    }

    @Test
    void doesNotInsertFaqWhenEmbeddingFails() {
        when(knowledgeItemMapper.selectCount(any())).thenReturn(0L);
        when(embeddingService.embedBatch(any())).thenReturn(List.of(new float[0]));

        assertThatThrownBy(() -> service.publish(draft()))
            .isInstanceOf(FaqDraftService.FaqDraftException.class)
            .hasMessageContaining("向量生成失败");
        verify(knowledgeItemMapper, never()).insert(any(BotKnowledgeItem.class));
    }

    private BotFaqDraft draft() {
        BotFaqDraft draft = new BotFaqDraft();
        draft.setClusterId(10L);
        draft.setQuestion("电子合同套餐怎么收费？");
        draft.setAnswer("点签按照套餐份数收费。");
        draft.setKeywords("套餐价格,电子合同多少钱一份？");
        draft.setSimilarQuestionsJson("[\"电子合同套餐怎么收费？\",\"电子合同多少钱一份？\"]");
        return draft;
    }
}
