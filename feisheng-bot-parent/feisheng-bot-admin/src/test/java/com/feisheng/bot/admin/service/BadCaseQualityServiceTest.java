package com.feisheng.bot.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotFaqRegressionRun;
import com.feisheng.bot.admin.entity.BotUnmatchedQuestion;
import com.feisheng.bot.admin.mapper.BotFaqRegressionRunMapper;
import com.feisheng.bot.admin.mapper.BotUnmatchedQuestionMapper;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BadCaseQualityServiceTest {
    @Test
    void summarizesAllTriggersAndRegressionChanges() throws Exception {
        BotUnmatchedQuestionMapper unmatchedMapper = mock(BotUnmatchedQuestionMapper.class);
        BotFaqRegressionRunMapper runMapper = mock(BotFaqRegressionRunMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(unmatchedMapper.selectList(any())).thenReturn(List.of(
            question(3, 0, "NO_ANSWER,NEW_TRIGGER"),
            question(2, 1, "LOW_RATING")));
        when(runMapper.selectList(any())).thenReturn(List.of(
            run(2L, 8, 10, objectMapper.writeValueAsString(List.of(
                new FaqRegressionService.FailureSnapshot("case-1", "怎么重置密码", List.of("FAQ引用不正确"))))),
            run(1L, 6, 10, objectMapper.writeValueAsString(List.of(
                new FaqRegressionService.FailureSnapshot("case-1", "怎么重置密码", List.of("FAQ引用不正确")))))));

        BadCaseQualityService.QualitySummary result = new BadCaseQualityService(
            unmatchedMapper, runMapper, objectMapper).summarize();

        assertThat(result.questionCount()).isEqualTo(2);
        assertThat(result.pendingQuestionCount()).isEqualTo(1);
        assertThat(result.totalOccurrenceCount()).isEqualTo(5);
        assertThat(result.resolutionRate()).isEqualTo(0.5);
        assertThat(result.triggerCounts())
            .extracting(BadCaseQualityService.TriggerMetric::triggerType)
            .containsExactly("NEW_TRIGGER", "NO_ANSWER", "LOW_RATING");
        assertThat(result.passRateDelta()).isEqualTo(0.2);
        assertThat(result.repeatedFailures())
            .containsExactly(new BadCaseQualityService.RepeatedFailure("怎么重置密码", 2));
    }

    private BotUnmatchedQuestion question(int count, int resolved, String triggers) {
        BotUnmatchedQuestion question = new BotUnmatchedQuestion();
        question.setSimilarCount(count);
        question.setIsResolved(resolved);
        question.setTriggerTypes(triggers);
        return question;
    }

    private BotFaqRegressionRun run(Long id, int passed, int executed, String failures) {
        BotFaqRegressionRun run = new BotFaqRegressionRun();
        run.setId(id);
        run.setPassed(passed == executed ? 1 : 0);
        run.setPassedCaseCount(passed);
        run.setExecutedCaseCount(executed);
        run.setPublishedDraftCount(2);
        run.setDatasetCaseCount(executed);
        run.setFailedCaseCount(executed - passed);
        run.setFailedCasesJson(failures);
        run.setCreateTime(new Date(id));
        return run;
    }
}
