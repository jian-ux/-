package com.feisheng.bot.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotFaqDraft;
import com.feisheng.bot.admin.entity.BotKnowledgeItem;
import com.feisheng.bot.admin.entity.BotQuestionCluster;
import com.feisheng.bot.admin.entity.BotQuestionClusterItem;
import com.feisheng.bot.admin.mapper.BotFaqDraftMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeItemMapper;
import com.feisheng.bot.admin.mapper.BotQuestionClusterItemMapper;
import com.feisheng.bot.admin.mapper.BotQuestionClusterMapper;
import com.feisheng.bot.core.dto.ChatResponse;
import com.feisheng.bot.core.service.impl.AiModelServiceImpl;
import com.feisheng.bot.core.service.impl.RagRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyBoolean;

@ExtendWith(MockitoExtension.class)
class FaqDraftServiceTest {
    @Mock private BotFaqDraftMapper draftMapper;
    @Mock private BotQuestionClusterMapper clusterMapper;
    @Mock private BotQuestionClusterItemMapper clusterItemMapper;
    @Mock private BotKnowledgeItemMapper knowledgeItemMapper;
    @Mock private RagRetrievalService retrievalService;
    @Mock private AiModelServiceImpl aiModelService;
    @Mock private FaqPublicationService publicationService;

    private FaqDraftService service;

    @BeforeEach
    void setUp() {
        service = new FaqDraftService(draftMapper, clusterMapper, clusterItemMapper,
            knowledgeItemMapper, retrievalService, aiModelService, publicationService,
            new ObjectMapper());
    }

    @Test
    void createsBlockedDraftWithoutCallingModelWhenEvidenceIsMissing() {
        stubCluster();
        when(retrievalService.retrieve("电子合同套餐价格", false)).thenReturn(
            new RagRetrievalService.RetrievalResult(false, false, null, null,
                0d, "no_answer", true, List.of(), List.of()));
        doAnswer(invocation -> {
            ((BotFaqDraft) invocation.getArgument(0)).setId(30L);
            return 1;
        }).when(draftMapper).insert(any(BotFaqDraft.class));

        FaqDraftService.DraftView result = service.generate(10L, 7L);

        assertThat(result.status()).isEqualTo("DRAFT");
        assertThat(result.evidenceStatus()).isEqualTo("MISSING");
        assertThat(result.answer()).isEmpty();
        assertThat(result.similarQuestions()).containsExactly(
            "电子合同多少钱一份", "有哪些合同套餐");
        verify(aiModelService, never()).chat(anyString(), anyString());
    }

    @Test
    void generatesGroundedDraftAndReportsSimilarExistingFaq() {
        stubCluster();
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("title", "点签套餐说明");
        citation.put("snippet", "点签按照套餐份数收费");
        citation.put("score", 0.91d);
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("itemId", 88L);
        candidate.put("score", 0.91d);
        when(retrievalService.retrieve("电子合同套餐价格", false)).thenReturn(
            new RagRetrievalService.RetrievalResult(true, false, null,
                "【企业内部事实】点签按照套餐份数收费", 0.91d, "rag", true,
                List.of(citation), List.of(candidate)));
        when(aiModelService.chat(anyString(), anyString())).thenReturn(
            new ChatResponse("{\"answer\":\"点签按照套餐份数收费。\",\"keywords\":[\"套餐价格\"]}",
                true, "model-a", "provider-a", 10, 10));
        doAnswer(invocation -> {
            ((BotFaqDraft) invocation.getArgument(0)).setId(31L);
            return 1;
        }).when(draftMapper).insert(any(BotFaqDraft.class));

        FaqDraftService.DraftView result = service.generate(10L, 7L);

        assertThat(result.evidenceStatus()).isEqualTo("SUPPORTED");
        assertThat(result.answer()).isEqualTo("点签按照套餐份数收费。");
        assertThat(result.keywords()).contains("套餐价格");
        assertThat(result.evidence()).hasSize(1);
        assertThat(result.duplicateItemId()).isEqualTo(88L);
        assertThat(result.generatorModel()).isEqualTo("provider-a/model-a");
    }

    @Test
    void returnsExistingDraftWithoutRegeneratingWhenRequestIsRetried() {
        stubCluster();
        BotFaqDraft existing = new BotFaqDraft();
        existing.setId(32L);
        existing.setClusterId(10L);
        existing.setStatus("DRAFT");
        existing.setEvidenceStatus("SUPPORTED");
        existing.setQuestion("电子合同套餐价格");
        existing.setAnswer("点签按照套餐份数收费。");
        when(draftMapper.selectOne(any())).thenReturn(existing);

        FaqDraftService.DraftView result = service.generate(10L, 7L);

        assertThat(result.id()).isEqualTo(32L);
        assertThat(result.answer()).isEqualTo("点签按照套餐份数收费。");
        verify(retrievalService, never()).retrieve(anyString(), anyBoolean());
        verify(aiModelService, never()).chat(anyString(), anyString());
        verify(draftMapper, never()).insert(any(BotFaqDraft.class));
    }

    @Test
    void refusesToPublishDraftWithoutCurrentEvidence() {
        BotFaqDraft draft = new BotFaqDraft();
        draft.setId(40L);
        draft.setStatus("DRAFT");
        draft.setEvidenceStatus("MISSING");
        draft.setQuestion("问题");
        draft.setAnswer("答案");
        when(draftMapper.selectById(40L)).thenReturn(draft);

        assertThatThrownBy(() -> service.publish(40L, 7L))
            .isInstanceOf(FaqDraftService.FaqDraftException.class)
            .hasMessageContaining("知识依据不足");
        verify(publicationService, never()).publish(any());
    }

    private void stubCluster() {
        BotQuestionCluster cluster = new BotQuestionCluster();
        cluster.setId(10L);
        cluster.setRunId(5L);
        cluster.setTitle("电子合同套餐价格");
        cluster.setIgnored(0);
        when(clusterMapper.selectById(10L)).thenReturn(cluster);
        when(clusterItemMapper.selectList(any())).thenReturn(List.of(
            item(1L, "电子合同多少钱一份"),
            item(2L, "有哪些合同套餐")));
    }

    private BotQuestionClusterItem item(Long id, String question) {
        BotQuestionClusterItem item = new BotQuestionClusterItem();
        item.setId(id);
        item.setQuestion(question);
        return item;
    }
}
