package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotUnmatchedQuestion;
import com.feisheng.bot.admin.mapper.BotUnmatchedQuestionMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnmatchedQuestionControllerTest {
    @Test
    void listAddsImprovementAdviceForEachQuestion() {
        BotUnmatchedQuestionMapper mapper = mock(BotUnmatchedQuestionMapper.class);
        BotUnmatchedQuestion question = new BotUnmatchedQuestion();
        question.setQuestion("点签支持某项功能吗");
        question.setTriggerTypes("LOW_CONFIDENCE,LOW_RATING");
        Page<BotUnmatchedQuestion> page = new Page<>();
        page.setRecords(List.of(question));
        when(mapper.selectPage(any(Page.class), any())).thenReturn(page);

        new UnmatchedQuestionController(mapper).list(1, 10);

        assertThat(question.getImprovementAdvice())
            .extracting(item -> item.recommendedAction())
            .containsExactly("RETRIEVAL", "ANSWER_REVIEW");
    }

    @Test
    void savesSimpleHumanReviewOnTheExistingBadCase() {
        BotUnmatchedQuestionMapper mapper = mock(BotUnmatchedQuestionMapper.class);
        BotUnmatchedQuestion question = new BotUnmatchedQuestion();
        question.setId(7L);
        when(mapper.selectById(7L)).thenReturn(question);

        new UnmatchedQuestionController(mapper).review(7L,
            new UnmatchedQuestionController.ReviewRequest(
                "CLARIFY", false, "追问错误", "缺少合同类型槽位"), null);

        verify(mapper).updateById(question);
        assertThat(question.getReviewStatus()).isEqualTo("REVIEWED");
        assertThat(question.getReviewDecision()).isEqualTo("CLARIFY");
        assertThat(question.getReviewCorrect()).isEqualTo(0);
        assertThat(question.getReviewCategory()).isEqualTo("追问错误");
    }
}
