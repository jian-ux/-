package com.feisheng.bot.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotFaqDraft;
import com.feisheng.bot.admin.entity.BotFaqRegressionRun;
import com.feisheng.bot.admin.mapper.BotFaqDraftMapper;
import com.feisheng.bot.admin.mapper.BotFaqRegressionRunMapper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FaqRegressionServiceTest {
    @Mock private BotFaqDraftMapper draftMapper;
    @Mock private BotFaqRegressionRunMapper regressionRunMapper;
    @Mock private DialogEvaluationService evaluationService;

    private FaqRegressionService service;

    @BeforeEach
    void setUp() {
        service = new FaqRegressionService(
            draftMapper, regressionRunMapper, evaluationService, new ObjectMapper());
    }

    @Test
    void evaluatesPublishedStandardQuestionsBeforeDistinctVariants() {
        when(draftMapper.selectList(any())).thenReturn(List.of(
            draft(10L, 70L, "怎么重置密码", "[\"怎么重置密码\",\"密码忘了怎么办\"]"),
            draft(11L, 71L, "怎么认证企业", "[\"企业认证怎么办\"]")));
        DialogEvaluationService.DialogEvaluationReport evaluation =
            mock(DialogEvaluationService.DialogEvaluationReport.class);
        when(evaluation.cases()).thenReturn(List.of(
            passedCase("faq-10-1", "怎么重置密码", 70L),
            passedCase("faq-11-2", "怎么认证企业", 71L),
            passedCase("faq-10-3", "密码忘了怎么办", 70L),
            passedCase("faq-11-4", "企业认证怎么办", 71L)));
        when(evaluationService.evaluate(any())).thenReturn(evaluation);

        FaqRegressionService.RegressionReport result = service.evaluate(null, "v2");

        assertThat(result.passed()).isTrue();
        assertThat(result.publishedDraftCount()).isEqualTo(2);
        assertThat(result.datasetCaseCount()).isEqualTo(4);
        assertThat(result.executedCaseCount()).isEqualTo(4);
        assertThat(result.truncated()).isFalse();
        verify(regressionRunMapper).insert(any(BotFaqRegressionRun.class));
        ArgumentCaptor<DialogEvaluationService.DialogEvaluationRequest> captor =
            ArgumentCaptor.forClass(DialogEvaluationService.DialogEvaluationRequest.class);
        verify(evaluationService).evaluate(captor.capture());
        assertThat(captor.getValue().promptVersion()).isEqualTo("v2");
        assertThat(captor.getValue().cases()).extracting(item -> item.question()).containsExactly(
            "怎么重置密码", "怎么认证企业", "密码忘了怎么办", "企业认证怎么办");
        assertThat(captor.getValue().cases()).extracting(item -> item.expectedSourceId())
            .containsExactly(70L, 71L, 70L, 71L);
        assertThat(captor.getValue().cases()).extracting(item -> item.expectedAnswerDecision())
            .containsExactly("ANSWER", "ANSWER", "ANSWER_OR_PARTIAL", "ANSWER_OR_PARTIAL");
        assertThat(captor.getValue().cases()).allSatisfy(item -> {
            assertThat(item.expectedSourceType()).isEqualTo("faq");
            assertThat(item.expectedNeedsTransfer()).isFalse();
        });
    }

    @Test
    void rejectsRegressionWhenNothingHasBeenPublished() {
        when(draftMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.evaluate(null, null))
            .isInstanceOf(FaqDraftService.FaqDraftException.class)
            .hasMessageContaining("没有已发布FAQ");
    }

    @Test
    void failsRegressionWhenAnAnswerIsIncorrectlyTransferred() {
        when(draftMapper.selectList(any())).thenReturn(List.of(
            draft(10L, 70L, "怎么重置密码", "[]")));
        DialogEvaluationService.DialogEvaluationReport evaluation =
            mock(DialogEvaluationService.DialogEvaluationReport.class);
        DialogEvaluationService.DialogCaseResult transferred = new DialogEvaluationService.DialogCaseResult(
            "faq-10-1", "怎么重置密码", true, true, "ANSWER", "ANSWER", "answered",
            "rag_ai", "v1", true, 0.5, "请转人工处理", "faq", 70L, true,
            List.of(), List.of(), false, true, false, 99L, true, false, false,
            List.of(), false, 20L, List.of(), null);
        when(evaluation.cases()).thenReturn(List.of(transferred));
        when(evaluationService.evaluate(any())).thenReturn(evaluation);

        FaqRegressionService.RegressionReport result = service.evaluate(null, null);

        assertThat(result.passed()).isFalse();
        assertThat(result.passedCaseCount()).isZero();
        assertThat(result.failedCaseCount()).isEqualTo(1);
        assertThat(result.failedCases()).containsExactly(transferred);
    }

    private BotFaqDraft draft(Long id, Long itemId, String question, String variantsJson) {
        BotFaqDraft draft = new BotFaqDraft();
        draft.setId(id);
        draft.setStatus(FaqDraftService.PUBLISHED);
        draft.setPublishedItemId(itemId);
        draft.setQuestion(question);
        draft.setSimilarQuestionsJson(variantsJson);
        return draft;
    }

    private DialogEvaluationService.DialogCaseResult passedCase(
            String id, String question, Long itemId) {
        return new DialogEvaluationService.DialogCaseResult(
            id, question, true, true, "ANSWER", "ANSWER", "answered", "rag_ai", "v2",
            true, 0.9, "测试回答", "faq", itemId, true, List.of(), List.of(), false,
            false, true, null, false, false, false, List.of(), false, 20L, List.of(), null);
    }
}
