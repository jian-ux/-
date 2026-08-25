package com.feisheng.bot.admin.controller;

import com.feisheng.bot.admin.entity.BotConversation;
import com.feisheng.bot.admin.entity.BotMessage;
import com.feisheng.bot.admin.mapper.BotConversationMapper;
import com.feisheng.bot.admin.mapper.BotConversationTagMapper;
import com.feisheng.bot.admin.mapper.BotMessageMapper;
import com.feisheng.bot.admin.service.ConversationImageService;
import com.feisheng.bot.core.service.impl.UnmatchedQuestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationAdminControllerTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void recordsLowRatingForTheLatestCustomerQuestion(int score) {
        BotConversationMapper conversationMapper = mock(BotConversationMapper.class);
        BotMessageMapper messageMapper = mock(BotMessageMapper.class);
        UnmatchedQuestionService badCaseService = mock(UnmatchedQuestionService.class);
        BotConversation conversation = new BotConversation();
        BotMessage latestQuestion = new BotMessage();
        latestQuestion.setContent("怎么签署合同");
        when(conversationMapper.selectById(7L)).thenReturn(conversation);
        when(messageMapper.selectOne(any())).thenReturn(latestQuestion);
        ConversationAdminController controller = controller(
            conversationMapper, messageMapper, badCaseService);

        controller.updateCsat(7L, Map.of("csatScore", score));

        verify(conversationMapper).updateById(conversation);
        verify(badCaseService).recordBadCase(
            eq("怎么签署合同"), eq(Set.of("LOW_RATING")), argThat(context ->
                context.conversationId().equals(7L)
                    && context.csatScore().equals(score)
                    && "rated".equals(context.answerStatus())
                    && "csat".equals(context.source())));
    }

    @Test
    void doesNotRecordRepeatedLowRating() {
        BotConversationMapper conversationMapper = mock(BotConversationMapper.class);
        BotMessageMapper messageMapper = mock(BotMessageMapper.class);
        UnmatchedQuestionService badCaseService = mock(UnmatchedQuestionService.class);
        BotConversation conversation = new BotConversation();
        conversation.setCsatScore(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation);
        ConversationAdminController controller = controller(
            conversationMapper, messageMapper, badCaseService);

        controller.updateCsat(7L, Map.of("csatScore", 2));

        verify(conversationMapper).updateById(conversation);
        verify(badCaseService, never()).recordBadCase(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5})
    void doesNotRecordAcceptableRating(int score) {
        BotConversationMapper conversationMapper = mock(BotConversationMapper.class);
        BotMessageMapper messageMapper = mock(BotMessageMapper.class);
        UnmatchedQuestionService badCaseService = mock(UnmatchedQuestionService.class);
        BotConversation conversation = new BotConversation();
        when(conversationMapper.selectById(7L)).thenReturn(conversation);
        ConversationAdminController controller = controller(
            conversationMapper, messageMapper, badCaseService);

        controller.updateCsat(7L, Map.of("csatScore", score));

        verify(conversationMapper).updateById(conversation);
        verify(badCaseService, never()).recordBadCase(any(), any(), any());
    }

    private ConversationAdminController controller(
            BotConversationMapper conversationMapper,
            BotMessageMapper messageMapper,
            UnmatchedQuestionService badCaseService) {
        return new ConversationAdminController(
            conversationMapper, messageMapper, mock(BotConversationTagMapper.class),
            mock(ConversationImageService.class), badCaseService);
    }
}
