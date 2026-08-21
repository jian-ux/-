package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotFaqDraft;
import com.feisheng.bot.admin.entity.BotUnmatchedQuestion;
import com.feisheng.bot.admin.entity.BotQuestionCluster;
import com.feisheng.bot.admin.entity.BotQuestionClusterItem;
import com.feisheng.bot.admin.entity.BotQuestionClusterRun;
import com.feisheng.bot.admin.mapper.BotFaqDraftMapper;
import com.feisheng.bot.admin.mapper.BotQuestionClusterItemMapper;
import com.feisheng.bot.admin.mapper.BotQuestionClusterMapper;
import com.feisheng.bot.admin.mapper.BotQuestionClusterRunMapper;
import com.feisheng.bot.admin.mapper.BotUnmatchedQuestionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuestionClusteringServiceTest {
    @Mock private BotUnmatchedQuestionMapper mapper;
    @Mock private EmbeddingService embeddingService;
    @Mock private BotQuestionClusterRunMapper clusterRunMapper;
    @Mock private BotQuestionClusterMapper clusterMapper;
    @Mock private BotQuestionClusterItemMapper clusterItemMapper;
    @Mock private BotFaqDraftMapper faqDraftMapper;

    @Test
    void groupsNormalizedSynonymsAndLeavesUnrelatedQuestionAsNoise() {
        when(mapper.selectList(any())).thenReturn(List.of(
            question(1L, "企业认证怎么做？", 3),
            question(2L, "公司认证怎么做", 2),
            question(3L, "怎么登录？", 1)));
        when(embeddingService.embedBatch(any())).thenReturn(List.of(
            new float[0], new float[0], new float[0]));
        when(embeddingService.descriptor()).thenReturn(
            new EmbeddingService.EmbeddingDescriptor("", ""));

        QuestionClusteringService.ClusterResult result =
            new QuestionClusteringService(mapper, embeddingService)
                .cluster(false, 500, 0.82, 2);

        assertThat(result.questionCount()).isEqualTo(3);
        assertThat(result.clusterCount()).isEqualTo(1);
        assertThat(result.noiseCount()).isEqualTo(1);
        assertThat(result.clusters().get(0).title()).isEqualTo("企业认证怎么做？");
        assertThat(result.clusters().get(0).totalOccurrences()).isEqualTo(5);
    }

    @Test
    void usesVectorsForSemanticSimilarityWhenWordsDiffer() {
        when(mapper.selectList(any())).thenReturn(List.of(
            question(1L, "如何完成企业认证", 1),
            question(2L, "公司资质审核流程", 1),
            question(3L, "怎么登录后台", 1)));
        when(embeddingService.embedBatch(any())).thenReturn(List.of(
            new float[]{1f, 0f}, new float[]{0.99f, 0.01f}, new float[]{0f, 1f}));
        when(embeddingService.descriptor()).thenReturn(
            new EmbeddingService.EmbeddingDescriptor("embed", "v1"));

        QuestionClusteringService.ClusterResult result =
            new QuestionClusteringService(mapper, embeddingService)
                .cluster(false, 500, 0.82, 2);

        assertThat(result.embeddingUsed()).isTrue();
        assertThat(result.embeddingModel()).isEqualTo("embed");
        assertThat(result.clusterCount()).isEqualTo(1);
        assertThat(result.noiseCount()).isEqualTo(1);
    }

    @Test
    void excludesResolvedQuestionsByDefault() {
        when(mapper.selectList(any())).thenReturn(List.of(
            question(1L, "企业认证怎么做", 1)));
        when(embeddingService.embedBatch(any())).thenReturn(List.of(new float[0]));
        when(embeddingService.descriptor()).thenReturn(
            new EmbeddingService.EmbeddingDescriptor("", ""));

        QuestionClusteringService.ClusterResult result =
            new QuestionClusteringService(mapper, embeddingService)
                .cluster(false, 500, 0.82, 2);

        assertThat(result.questionCount()).isEqualTo(1);
    }

    @Test
    void persistsReviewBatchAndClusterMembers() {
        when(mapper.selectList(any())).thenReturn(List.of(
            question(1L, "企业认证怎么做", 2),
            question(2L, "公司认证怎么做", 1)));
        when(embeddingService.embedBatch(any())).thenReturn(List.of(
            new float[0], new float[0]));
        when(embeddingService.descriptor()).thenReturn(
            new EmbeddingService.EmbeddingDescriptor("", ""));
        doAnswer(invocation -> {
            ((BotQuestionClusterRun) invocation.getArgument(0)).setId(10L);
            return 1;
        }).when(clusterRunMapper).insert(any(BotQuestionClusterRun.class));
        doAnswer(invocation -> {
            ((BotQuestionCluster) invocation.getArgument(0)).setId(20L);
            return 1;
        }).when(clusterMapper).insert(any(BotQuestionCluster.class));
        doAnswer(invocation -> 1).when(clusterItemMapper).insert(any(BotQuestionClusterItem.class));

        QuestionClusteringService.ClusterReviewResult result =
            new QuestionClusteringService(mapper, embeddingService, clusterRunMapper,
                clusterMapper, clusterItemMapper).runAndSave(false, 500, 0.82, 2);

        assertThat(result.runId()).isEqualTo(10L);
        assertThat(result.clusterCount()).isEqualTo(1);
        assertThat(result.clusters().get(0).id()).isEqualTo(20L);
        assertThat(result.clusters().get(0).questions()).hasSize(2);
        verify(clusterRunMapper).insert(any(BotQuestionClusterRun.class));
        verify(clusterMapper).insert(any(BotQuestionCluster.class));
        verify(clusterItemMapper, times(2)).insert(any(BotQuestionClusterItem.class));
    }

    @Test
    void mergesClustersByCopyingMembersAndKeepingSourcesIgnored() {
        BotQuestionCluster target = cluster(10L, 5L, 1, "认证怎么做");
        BotQuestionCluster source = cluster(11L, 5L, 2, "公司认证流程");
        BotQuestionClusterItem sourceItem = clusterItem(21L, 11L, 2L, "公司认证怎么做", 2);
        when(clusterMapper.selectById(10L)).thenReturn(target);
        when(clusterMapper.selectList(any())).thenReturn(List.of(source));
        when(clusterItemMapper.selectList(any())).thenReturn(List.of(sourceItem),
            List.of(clusterItem(20L, 10L, 1L, "认证怎么做", 1), sourceItem));
        doAnswer(invocation -> {
            ((BotQuestionClusterItem) invocation.getArgument(0)).setId(30L);
            return 1;
        }).when(clusterItemMapper).insert(any(BotQuestionClusterItem.class));
        when(clusterMapper.updateById(any(BotQuestionCluster.class))).thenReturn(1);

        QuestionClusteringService.MutationResult result =
            new QuestionClusteringService(mapper, embeddingService, clusterRunMapper,
                clusterMapper, clusterItemMapper).merge(10L, List.of(10L, 11L));

        assertThat(result.success()).isTrue();
        assertThat(source.getIgnored()).isEqualTo(1);
        assertThat(source.getMergedIntoId()).isEqualTo(10L);
        verify(clusterItemMapper).insert(any(BotQuestionClusterItem.class));
    }

    @Test
    void splitsSelectedQuestionsIntoNewCluster() {
        BotQuestionCluster source = cluster(10L, 5L, 1, "认证怎么做");
        BotQuestionClusterItem first = clusterItem(20L, 10L, 1L, "企业认证怎么做", 2);
        BotQuestionClusterItem second = clusterItem(21L, 10L, 2L, "企业认证流程", 1);
        when(clusterMapper.selectById(10L)).thenReturn(source);
        when(clusterItemMapper.selectList(any())).thenReturn(List.of(first, second), List.of(second));
        when(clusterMapper.selectList(any())).thenReturn(List.of(source));
        doAnswer(invocation -> {
            ((BotQuestionCluster) invocation.getArgument(0)).setId(30L);
            return 1;
        }).when(clusterMapper).insert(any(BotQuestionCluster.class));
        when(clusterMapper.updateById(any(BotQuestionCluster.class))).thenReturn(1);

        QuestionClusteringService.MutationResult result =
            new QuestionClusteringService(mapper, embeddingService, clusterRunMapper,
                clusterMapper, clusterItemMapper).split(10L, List.of(1L), "企业认证怎么做");

        assertThat(result.success()).isTrue();
        verify(clusterMapper).insert(any(BotQuestionCluster.class));
        verify(clusterItemMapper).update(any(), any());
    }

    @Test
    void deletesClusterAndMembersWithoutDeletingSourceQuestions() {
        BotQuestionCluster cluster = cluster(10L, 5L, 1, "认证怎么做");
        BotQuestionClusterItem item = clusterItem(20L, 10L, 1L, "企业认证怎么做", 2);
        BotFaqDraft draft = new BotFaqDraft();
        draft.setId(30L);
        draft.setClusterId(10L);
        draft.setStatus("DRAFT");
        when(clusterMapper.selectById(10L)).thenReturn(cluster);
        when(faqDraftMapper.selectOne(any())).thenReturn(draft);
        when(clusterItemMapper.selectList(any())).thenReturn(List.of(item));
        when(clusterItemMapper.deleteById(20L)).thenReturn(1);
        when(faqDraftMapper.deleteById(30L)).thenReturn(1);
        when(clusterMapper.deleteById(10L)).thenReturn(1);

        QuestionClusteringService.MutationResult result =
            new QuestionClusteringService(mapper, embeddingService, clusterRunMapper,
                clusterMapper, clusterItemMapper, faqDraftMapper).delete(10L);

        assertThat(result.success()).isTrue();
        verify(clusterItemMapper).deleteById(20L);
        verify(faqDraftMapper).deleteById(30L);
        verify(clusterMapper).deleteById(10L);
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    @Test
    void refusesToDeleteClusterWithPublishedFaq() {
        BotQuestionCluster cluster = cluster(10L, 5L, 1, "认证怎么做");
        BotFaqDraft draft = new BotFaqDraft();
        draft.setId(30L);
        draft.setClusterId(10L);
        draft.setStatus("PUBLISHED");
        when(clusterMapper.selectById(10L)).thenReturn(cluster);
        when(faqDraftMapper.selectOne(any())).thenReturn(draft);

        QuestionClusteringService.MutationResult result =
            new QuestionClusteringService(mapper, embeddingService, clusterRunMapper,
                clusterMapper, clusterItemMapper, faqDraftMapper).delete(10L);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo(409);
        verify(clusterMapper, org.mockito.Mockito.never()).deleteById(10L);
        verify(clusterItemMapper, org.mockito.Mockito.never()).deleteById(20L);
    }

    @Test
    void returnsNotFoundWhenDeletingMissingCluster() {
        when(clusterMapper.selectById(404L)).thenReturn(null);

        QuestionClusteringService.MutationResult result =
            new QuestionClusteringService(mapper, embeddingService, clusterRunMapper,
                clusterMapper, clusterItemMapper, faqDraftMapper).delete(404L);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo(404);
    }

    private static BotUnmatchedQuestion question(Long id, String text, int count) {
        BotUnmatchedQuestion question = new BotUnmatchedQuestion();
        question.setId(id);
        question.setQuestion(text);
        question.setSimilarCount(count);
        question.setIsResolved(0);
        question.setCreateTime(new Date(id));
        return question;
    }

    private static BotQuestionCluster cluster(Long id, Long runId, int number, String title) {
        BotQuestionCluster cluster = new BotQuestionCluster();
        cluster.setId(id);
        cluster.setRunId(runId);
        cluster.setClusterNumber(number);
        cluster.setTitle(title);
        cluster.setQuestionCount(2);
        cluster.setTotalOccurrences(3);
        cluster.setCohesion(0.9d);
        cluster.setIgnored(0);
        return cluster;
    }

    private static BotQuestionClusterItem clusterItem(Long id, Long clusterId, Long questionId,
                                                       String text, int count) {
        BotQuestionClusterItem item = new BotQuestionClusterItem();
        item.setId(id);
        item.setClusterId(clusterId);
        item.setUnmatchedQuestionId(questionId);
        item.setQuestion(text);
        item.setAnalysisQuestion(text);
        item.setSimilarCount(count);
        item.setSimilarityToTitle(0.9d);
        return item;
    }
}
