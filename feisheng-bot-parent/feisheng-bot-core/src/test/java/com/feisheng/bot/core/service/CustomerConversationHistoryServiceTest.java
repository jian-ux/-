package com.feisheng.bot.core.service;

import com.feisheng.bot.core.entity.BotConversation;
import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.mapper.BotConversationMapper;
import com.feisheng.bot.core.mapper.BotMessageMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerConversationHistoryServiceTest {
    @Mock private BotConversationMapper conversationMapper;
    @Mock private BotMessageMapper messageMapper;

    @Test
    void loadsOtherConversationCustomerAndAiMessagesOnly() {
        BotConversation conversation = new BotConversation();
        conversation.setId(12L);
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation));
        BotMessage user = message(2L, 12L, "user", "我之前咨询过认证");
        BotMessage ai = message(3L, 12L, "ai", "可以从控制台进入认证");
        BotMessage system = message(4L, 12L, "system", "内部事件");
        when(messageMapper.selectList(any())).thenReturn(List.of(user, ai, system));

        CustomerConversationHistoryService service = new CustomerConversationHistoryService(
            conversationMapper, messageMapper, new SensitiveDataService(""));
        String context = service.contextFor("web", "u-1", 99L);

        assertTrue(context.contains("之前咨询过认证"));
        assertTrue(context.contains("可以从控制台进入认证"));
        assertFalse(context.contains("内部事件"));
        assertTrue(context.contains("不是知识库事实"));
        verify(messageMapper).selectList(any());
    }

    @Test
    void excludesPlaygroundAndCurrentConversation() {
        CustomerConversationHistoryService service = new CustomerConversationHistoryService(
            conversationMapper, messageMapper, new SensitiveDataService(""));

        assertTrue(service.contextFor("playground", "trial", 1L).isBlank());
        assertTrue(service.contextFor("web", "u-1", 1L).isBlank());
    }

    private BotMessage message(Long id, Long conversationId, String role, String content) {
        BotMessage message = new BotMessage();
        message.setId(id);
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setCreateTime(new Date(id * 1000));
        return message;
    }
}
