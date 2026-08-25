package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.feisheng.bot.knowledge.service.MinioStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HumanHandoffServiceTest {
    @Mock private BotTicketMapper ticketMapper;
    @Mock private BotTicketRecordMapper recordMapper;
    @Mock private BotConversationMapper conversationMapper;
    @Mock private BotMessageMapper messageMapper;
    @Mock private SysUserMapper userMapper;
    @Mock private ChannelReplyDispatcher replyDispatcher;
    @Mock private MinioStorageService storageService;
    @Mock private ConversationImageService imageService;

    private HumanHandoffService service;

    @BeforeEach
    void setUp() {
        service = new HumanHandoffService(ticketMapper, recordMapper, conversationMapper,
            messageMapper, userMapper, replyDispatcher,
            new SensitiveDataService("18689633999"), new ObjectMapper(), storageService,
            imageService);
        lenient().when(messageMapper.selectOne(any(Wrapper.class)))
            .thenReturn(customerMessage(), (BotMessage) null);
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
    void listsTicketsWithNormalizedChannelAndCustomerFilters() {
        Page<BotTicket> page = new Page<>(1, 10);
        page.setRecords(List.of(ticket("pending", null)));
        when(ticketMapper.selectAdminPage(any(Page.class), eq("pending"),
            eq(9L), eq("dingtalk"), eq("张三"))).thenReturn(page);

        Page<BotTicket> result = service.list(
            0, 500, " PENDING ", 9L, " DingTalk ", " 张三 ");

        assertEquals(1, result.getRecords().size());
        verify(ticketMapper).selectAdminPage(any(Page.class), eq("pending"),
            eq(9L), eq("dingtalk"), eq("张三"));
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
    void storesAndDispatchesDingTalkHumanImage() {
        BotTicket ticket = ticket("processing", 9L);
        BotConversation conversation = conversation();
        conversation.setChannelType("dingtalk");
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(conversationMapper.selectById(3L)).thenReturn(conversation);
        when(userMapper.selectById(9L)).thenReturn(operator());
        when(replyDispatcher.dispatchImage(any(), any(), any(), any())).thenReturn(
            new ChannelReplyDispatcher.DispatchResult(true, "SENT", "dingtalk", null));
        MockMultipartFile file = new MockMultipartFile(
            "file", "guide.png", "image/png", new byte[] {1, 2, 3});

        HumanHandoffService.ReplyResult result = service.replyImage(7L, 9L, file);

        assertTrue(result.delivered());
        assertEquals("SENT", result.deliveryStatus());
        ArgumentCaptor<BotMessage> messageCaptor = ArgumentCaptor.forClass(BotMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertEquals("image", messageCaptor.getValue().getContentType());
        assertEquals("guide.png", messageCaptor.getValue().getContent());
        verify(replyDispatcher).dispatchImage(
            eq(conversation), any(), eq("guide.png"), eq("image/png"));
    }

    @Test
    void sendsHumanTextAndImageInOneDingTalkMarkdownMessage() throws Exception {
        BotTicket ticket = ticket("processing", 9L);
        BotConversation conversation = conversation();
        conversation.setChannelType("dingtalk");
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(conversationMapper.selectById(3L)).thenReturn(conversation);
        when(userMapper.selectById(9L)).thenReturn(operator());
        when(storageService.upload(any(byte[].class), eq("guide.png"), eq("image/png")))
            .thenReturn(new MinioStorageService.UploadResult(
                "knowledge", "human/guide.png", "png", 3));
        when(imageService.url(any())).thenReturn(
            "https://bot.example.com/api/public/conversation-images/3/55?signature=test");
        when(replyDispatcher.dispatchMarkdown(
            eq(conversation), eq("客服回复"), any(), eq(null)))
            .thenReturn(new ChannelReplyDispatcher.DispatchResult(
                true, "SENT", "dingtalk", null));
        MockMultipartFile file = new MockMultipartFile(
            "file", "guide.png", "image/png", new byte[] {1, 2, 3});

        HumanHandoffService.ReplyResult result = service.replyImage(
            7L, 9L, "请参考下图", 101L, file);

        assertTrue(result.delivered());
        ArgumentCaptor<BotMessage> messageCaptor = ArgumentCaptor.forClass(BotMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertEquals("mixed", messageCaptor.getValue().getContentType());
        assertEquals("请参考下图", messageCaptor.getValue().getContent());
        verify(replyDispatcher).dispatchMarkdown(
            eq(conversation), eq("客服回复"),
            eq("请参考下图\n\n![guide.png]"
                + "(https://bot.example.com/api/public/conversation-images/3/55?signature=test)"),
            eq(null));
    }

    @Test
    void rejectsHumanReplyWhenAiAlreadyAnsweredCustomerMessage() {
        BotTicket ticket = ticket("processing", 9L);
        BotConversation conversation = conversation();
        BotMessage customer = customerMessage();
        BotMessage aiReply = new BotMessage();
        aiReply.setId(102L);
        aiReply.setRole("ai");
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(conversationMapper.selectById(3L)).thenReturn(conversation);
        when(userMapper.selectById(9L)).thenReturn(operator());
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(customer, aiReply);

        BusinessException error = assertThrows(BusinessException.class,
            () -> service.reply(7L, 9L, "您好", 101L));

        assertEquals(409, error.getCode());
        verifyNoInteractions(replyDispatcher);
    }

    @Test
    void sendsDingTalkHumanReplyAsNaturalPlainText() {
        BotTicket ticket = ticket("processing", 9L);
        BotConversation conversation = conversation();
        conversation.setChannelType("dingtalk");
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(conversationMapper.selectById(3L)).thenReturn(conversation);
        when(userMapper.selectById(9L)).thenReturn(operator());
        when(replyDispatcher.dispatch(any(), any())).thenReturn(
            new ChannelReplyDispatcher.DispatchResult(true, "SENT", "dingtalk", null));

        service.reply(7L, 9L, "您好\n请问有什么可以帮助您的？");

        verify(replyDispatcher).dispatch(conversation,
            "您好\n请问有什么可以帮助您的？");
    }

    @Test
    void leavesNonDingTalkHumanReplyTextUnchanged() {
        BotTicket ticket = ticket("processing", 9L);
        BotConversation conversation = conversation();
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(conversationMapper.selectById(3L)).thenReturn(conversation);
        when(userMapper.selectById(9L)).thenReturn(operator());
        when(replyDispatcher.dispatch(any(), any())).thenReturn(
            new ChannelReplyDispatcher.DispatchResult(true, "STORED", "web", null));

        service.reply(7L, 9L, "您好");

        verify(replyDispatcher).dispatch(conversation, "您好");
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

    private BotMessage customerMessage() {
        BotMessage message = new BotMessage();
        message.setId(101L);
        message.setConversationId(3L);
        message.setRole("user");
        message.setContentType("text");
        message.setContent("客户问题");
        return message;
    }
}
