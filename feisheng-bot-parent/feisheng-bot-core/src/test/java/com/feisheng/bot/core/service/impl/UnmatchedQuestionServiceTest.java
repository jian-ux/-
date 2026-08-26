package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.core.entity.BotUnmatchedQuestion;
import com.feisheng.bot.core.mapper.BotUnmatchedQuestionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnmatchedQuestionServiceTest {
    @Mock private BotUnmatchedQuestionMapper mapper;

    @Test
    void insertsNormalizedBadCaseWithLatestDiagnostics() {
        when(mapper.selectOne(any())).thenReturn(null);
        UnmatchedQuestionService service = new UnmatchedQuestionService(mapper);
        UnmatchedQuestionService.BadCaseContext context =
            new UnmatchedQuestionService.BadCaseContext(
                12L, "answered", "rag_ai", 0.42, 680, 2,
                "ANSWER", "evidence_answer_retry");

        service.recordBadCase("  怎么   签署合同  ",
            List.of("low_confidence", "LOW_CONFIDENCE", "invalid?"), context);

        ArgumentCaptor<BotUnmatchedQuestion> captor =
            ArgumentCaptor.forClass(BotUnmatchedQuestion.class);
        verify(mapper).insert(captor.capture());
        BotUnmatchedQuestion created = captor.getValue();
        assertEquals("怎么 签署合同", created.getQuestion());
        assertEquals(1, created.getSimilarCount());
        assertEquals(0, created.getIsResolved());
        assertEquals("LOW_CONFIDENCE", created.getTriggerTypes());
        assertEquals(12L, created.getConversationId());
        assertEquals("answered", created.getLastAnswerStatus());
        assertEquals("ANSWER", created.getLastAnswerDecision());
        assertEquals("evidence_answer_retry", created.getLastReasonCode());
        assertEquals("rag_ai", created.getLastSource());
        assertEquals(0.42, created.getLastConfidence());
        assertEquals(680, created.getLastLatencyMs());
        assertEquals(2, created.getLastCsatScore());
    }

    @Test
    void incrementsExistingBadCaseAndMergesEachTriggerOnce() {
        BotUnmatchedQuestion existing = new BotUnmatchedQuestion();
        existing.setId(9L);
        existing.setQuestion("怎么签署合同");
        existing.setSimilarCount(3);
        existing.setIsResolved(0);
        existing.setTriggerTypes("NO_ANSWER,LOW_CONFIDENCE");
        existing.setReviewStatus("REVIEWED");
        existing.setReviewCorrect(1);
        when(mapper.selectOne(any())).thenReturn(existing);
        UnmatchedQuestionService service = new UnmatchedQuestionService(mapper);

        service.recordBadCase("怎么签署合同",
            List.of("NO_ANSWER", "guardrail", "GUARDRAIL"),
            new UnmatchedQuestionService.BadCaseContext(
                20L, "blocked", "safety", 0.31, 900, null));

        verify(mapper).updateById(existing);
        verify(mapper, never()).insert(any(BotUnmatchedQuestion.class));
        assertEquals(4, existing.getSimilarCount());
        assertEquals("NO_ANSWER,LOW_CONFIDENCE,GUARDRAIL", existing.getTriggerTypes());
        assertEquals(20L, existing.getConversationId());
        assertEquals("blocked", existing.getLastAnswerStatus());
        assertEquals("safety", existing.getLastSource());
        assertEquals(0.31, existing.getLastConfidence());
        assertEquals(900, existing.getLastLatencyMs());
        assertEquals("PENDING", existing.getReviewStatus());
        assertNotNull(existing.getUpdateTime());
    }

    @Test
    void reopensAResolvedBadCaseWhenTheQuestionReturns() {
        BotUnmatchedQuestion existing = new BotUnmatchedQuestion();
        existing.setId(10L);
        existing.setQuestion("怎么签署合同");
        existing.setSimilarCount(4);
        existing.setIsResolved(1);
        existing.setReviewStatus("REVIEWED");
        when(mapper.selectOne(any())).thenReturn(existing);

        new UnmatchedQuestionService(mapper).recordBadCase(
            "怎么签署合同", List.of("NO_ANSWER"),
            new UnmatchedQuestionService.BadCaseContext(
                21L, "no_answer", "no_answer", 0.1, 700, null));

        verify(mapper).updateById(existing);
        assertEquals(0, existing.getIsResolved());
        assertEquals(5, existing.getSimilarCount());
        assertEquals("PENDING", existing.getReviewStatus());
    }
}
