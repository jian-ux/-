package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.feisheng.bot.admin.entity.BotTicket;
import com.feisheng.bot.admin.mapper.BotTicketMapper;
import com.feisheng.bot.core.entity.BotConversation;
import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.mapper.BotConversationMapper;
import com.feisheng.bot.core.mapper.BotMessageMapper;
import com.feisheng.bot.core.service.HandoffCoordinator;
import com.feisheng.bot.core.service.SensitiveDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketHandoffCoordinatorTest {
    @Mock private BotConversationMapper conversationMapper;
    @Mock private BotMessageMapper messageMapper;
    @Mock private BotTicketMapper ticketMapper;

    private TicketHandoffCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new TicketHandoffCoordinator(conversationMapper, messageMapper,
            ticketMapper, new SensitiveDataService("18689633999"));
    }

    @Test
    void createsRedactedTicketAndUpdatesConversation() {
        BotConversation conversation = new BotConversation();
        conversation.setId(7L);
        conversation.setTitle("张三 13800138000 的售后咨询");
        conversation.setPriority("P2");
        conversation.setEmotionLabel("ANGER");
        conversation.setEmotionRisk("HIGH");
        conversation.setEmotionTrend("PERSISTENT");
        conversation.setNegativeStreak(3);
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(conversation);
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            message("user", "我的邮箱是buyer@example.com，冰箱不制冷"),
            message("ai", "正在核实")));

        HandoffCoordinator.HandoffResult result = coordinator.handoff(7L, "模型低置信度", "P1");

        ArgumentCaptor<BotTicket> ticketCaptor = ArgumentCaptor.forClass(BotTicket.class);
        verify(ticketMapper).insert(ticketCaptor.capture());
        BotTicket ticket = ticketCaptor.getValue();
        assertTrue(result.success());
        assertTrue(result.created());
        assertEquals("pending", ticket.getStatus());
        assertEquals("P1", ticket.getPriority());
        assertFalse(ticket.getTitle().contains("13800138000"));
        assertFalse(ticket.getDescription().contains("buyer@example.com"));
        assertTrue(ticket.getDescription().contains("[邮箱已脱敏]"));
        assertTrue(ticket.getDescription().contains(
            "情绪状态：愤怒；风险：高；趋势：持续；连续负面：3 轮"));
        assertEquals("transferred", conversation.getStatus());
        assertEquals("P1", conversation.getPriority());
        verify(conversationMapper).updateById(conversation);
    }

    @Test
    void reusesOpenTicket() {
        BotConversation conversation = new BotConversation();
        conversation.setId(8L);
        conversation.setPriority("P0");
        BotTicket existing = new BotTicket();
        existing.setId(99L);
        existing.setDescription("已有摘要");
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(conversation);
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        HandoffCoordinator.HandoffResult result = coordinator.handoff(8L, "再次转接", "P1");

        assertTrue(result.success());
        assertFalse(result.created());
        assertEquals(99L, result.ticketId());
        assertEquals("已有摘要", result.summary());
    }

    @Test
    void appendsRedactedCustomerSupplementToQueuedTicket() {
        BotTicket ticket = new BotTicket();
        ticket.setId(11L);
        ticket.setStatus("pending");
        ticket.setDescription("转人工原因：价格相关信息需要人工确认");
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);

        coordinator.recordUserMessage(7L, "10 人使用，联系人 13800138000");

        assertTrue(ticket.getDescription().contains("10 人使用"));
        assertFalse(ticket.getDescription().contains("13800138000"));
        verify(ticketMapper).updateById(ticket);
    }

    @Test
    void closesOnlyQueuedTicketWhenCustomerCancelsWaiting() {
        BotTicket ticket = new BotTicket();
        ticket.setId(12L);
        ticket.setStatus("pending");
        BotConversation conversation = new BotConversation();
        conversation.setId(7L);
        conversation.setStatus("transferred");
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(conversationMapper.selectById(7L)).thenReturn(conversation);

        boolean cancelled = coordinator.cancelWaitingHandoff(7L, "用户取消等待人工客服");

        assertTrue(cancelled);
        assertEquals("closed", ticket.getStatus());
        assertEquals("active", conversation.getStatus());
        assertEquals("CANCELLED", conversation.getHandoffStatus());
        verify(ticketMapper).updateById(ticket);
        verify(conversationMapper).updateById(conversation);
    }

    private BotMessage message(String role, String content) {
        BotMessage message = new BotMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
