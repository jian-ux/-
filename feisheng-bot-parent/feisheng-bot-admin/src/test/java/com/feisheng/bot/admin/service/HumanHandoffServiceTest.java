package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotConversation;
import com.feisheng.bot.admin.entity.BotMessage;
import com.feisheng.bot.admin.entity.BotTicket;
import com.feisheng.bot.admin.entity.BotTicketRecord;
import com.feisheng.bot.admin.entity.SysUser;
import com.feisheng.bot.admin.mapper.BotConversationMapper;
import com.feisheng.bot.admin.mapper.BotMessageMapper;
import com.feisheng.bot.admin.mapper.BotTicketMapper;
import com.feisheng.bot.admin.mapper.BotTicketRecordMapper;
import com.feisheng.bot.admin.mapper.SysUserMapper;
import com.feisheng.bot.common.exception.BusinessException;
import com.feisheng.bot.core.service.SensitiveDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HumanHandoffServiceTest {
    @Mock private BotTicketMapper ticketMapper;
    @Mock private BotTicketRecordMapper recordMapper;
    @Mock private BotConversationMapper conversationMapper;
    @Mock private BotMessageMapper messageMapper;
    @Mock private SysUserMapper userMapper;
    @Mock private ChannelReplyDispatcher replyDispatcher;

    private HumanHandoffService service;

    @BeforeEach
    void setUp() {
        service = new HumanHandoffService(ticketMapper, recordMapper, conversationMapper,
            messageMapper, userMapper, replyDispatcher,
            new SensitiveDataService("18689633999"), new ObjectMapper());
    }

    @Test
    void claimsTicketAndMarksConversationAsProcessing() {
        BotTicket ticket = ticket("pending", null);
        BotConversation conversation = conversation();
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(conversationMapper.selectById(3L)).thenReturn(conversation);
        when(userMapper.selectById(9L)).thenReturn(operator());

        BotTicket result = service.claim(7L, 9L);

        assertEquals("processing", result.getStatus());
        assertEquals(9L, result.getAssigneeId());
        assertEquals("客服小李", result.getAssigneeName());
        assertEquals("PROCESSING", conversation.getHandoffStatus());
        assertEquals("客服小李", conversation.getAssignedAgentName());
        verify(recordMapper).insert(any(BotTicketRecord.class));
    }

    @Test
    void storesRedactedHumanReplyAndDeliveryStatus() {
        BotTicket ticket = ticket("processing", 9L);
        BotConversation conversation = conversation();
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(conversationMapper.selectById(3L)).thenReturn(conversation);
        when(userMapper.selectById(9L)).thenReturn(operator());
        when(replyDispatcher.dispatch(any(), any())).thenReturn(
            new ChannelReplyDispatcher.DispatchResult(true, "STORED", "web", null));

        HumanHandoffService.ReplyResult result = service.reply(
            7L, 9L, "请联系 13800138000 继续处理");

        assertTrue(result.delivered());
        ArgumentCaptor<BotMessage> messageCaptor = ArgumentCaptor.forClass(BotMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        BotMessage message = messageCaptor.getValue();
        assertEquals("human", message.getRole());
        assertFalse(message.getContent().contains("13800138000"));
        assertTrue(message.getMetadata().contains("STORED"));
        assertEquals("PROCESSING", conversation.getHandoffStatus());
    }

    @Test
    void rejectsReplyFromDifferentAgent() {
        when(ticketMapper.selectOne(any(Wrapper.class)))
            .thenReturn(ticket("processing", 10L));
        when(userMapper.selectById(9L)).thenReturn(operator());

        BusinessException error = assertThrows(BusinessException.class,
            () -> service.reply(7L, 9L, "我来处理"));

        assertEquals(409, error.getCode());
    }

    @Test
    void resolvesTicketAndClosesConversation() {
        BotTicket ticket = ticket("processing", 9L);
        BotConversation conversation = conversation();
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(conversationMapper.selectById(3L)).thenReturn(conversation);
        when(userMapper.selectById(9L)).thenReturn(operator());

        HumanHandoffService.ResolveResult result = service.resolve(7L, 9L, "已协助处理");

        assertTrue(result.resolved());
        assertTrue(result.csatRequested());
        assertEquals("resolved", ticket.getStatus());
        assertEquals("closed", conversation.getStatus());
        assertEquals("RESOLVED", conversation.getHandoffStatus());
        verify(messageMapper).insert(any(BotMessage.class));
        verify(recordMapper).insert(any(BotTicketRecord.class));
    }

    private BotTicket ticket(String status, Long assigneeId) {
        BotTicket ticket = new BotTicket();
        ticket.setId(7L);
        ticket.setConversationId(3L);
        ticket.setStatus(status);
        ticket.setAssigneeId(assigneeId);
        ticket.setPriority("P1");
        return ticket;
    }

    private BotConversation conversation() {
        BotConversation conversation = new BotConversation();
        conversation.setId(3L);
        conversation.setChannelType("web");
        conversation.setChannelUserId("user-1");
        conversation.setStatus("transferred");
        return conversation;
    }

    private SysUser operator() {
        SysUser user = new SysUser();
        user.setId(9L);
        user.setUsername("agent-li");
        user.setRealName("客服小李");
        user.setStatus(1);
        return user;
    }
}
