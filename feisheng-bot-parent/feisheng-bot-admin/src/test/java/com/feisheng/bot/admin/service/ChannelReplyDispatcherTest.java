package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotChannelConfig;
import com.feisheng.bot.admin.entity.BotConversation;
import com.feisheng.bot.admin.mapper.BotChannelConfigMapper;
import com.feisheng.bot.gateway.client.DingTalkClient;
import com.feisheng.bot.gateway.client.WeChatWorkClient;
import com.feisheng.bot.gateway.service.DingTalkImageReplyDispatcher.ReplyTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelReplyDispatcherTest {
    @Mock private DingTalkClient dingTalkClient;
    @Mock private WeChatWorkClient weChatWorkClient;
    @Mock private BotChannelConfigMapper channelConfigMapper;

    private ChannelReplyDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ChannelReplyDispatcher(dingTalkClient, weChatWorkClient,
            channelConfigMapper, new ObjectMapper(), "", "");
    }

    @Test
    void returnsActionableDingTalkApiError() {
        when(channelConfigMapper.selectOne(any(Wrapper.class))).thenReturn(config());
        when(dingTalkClient.sendRobotMessage(
            "app-key", "app-secret", "robot-code", "staff-1", "你好"))
            .thenThrow(new IllegalStateException(
                "钉钉主动发送失败（HTTP 400，InvalidParameter）：userIds is invalid"));

        ChannelReplyDispatcher.DispatchResult result = dispatcher.dispatch(
            conversation(), "你好");

        assertFalse(result.delivered());
        assertEquals("FAILED", result.status());
        assertEquals("钉钉主动发送失败（HTTP 400，InvalidParameter）：userIds is invalid",
            result.error());
    }

    @Test
    void uploadsAndSendsDingTalkHumanImage() {
        byte[] image = {1, 2, 3};
        when(channelConfigMapper.selectOne(any(Wrapper.class))).thenReturn(config());
        when(dingTalkClient.uploadImage(
            "app-key", "app-secret", image, "guide.png", "image/png"))
            .thenReturn("@lAL-image");
        when(dingTalkClient.sendImageToUser(
            "app-key", "app-secret", "robot-code", "staff-1", "@lAL-image"))
            .thenReturn(true);

        ChannelReplyDispatcher.DispatchResult result = dispatcher.dispatchImage(
            conversation(), image, "guide.png", "image/png");

        assertTrue(result.delivered());
        assertEquals("SENT", result.status());
        verify(dingTalkClient).sendImageToUser(
            "app-key", "app-secret", "robot-code", "staff-1", "@lAL-image");
    }

    @Test
    void sendsHumanTextBackToOriginalDingTalkGroup() {
        when(channelConfigMapper.selectOne(any(Wrapper.class))).thenReturn(config());
        when(dingTalkClient.sendRobotMessageToGroup(
            "app-key", "app-secret", "robot-code", "cid-group", "你好"))
            .thenReturn(true);

        ChannelReplyDispatcher.DispatchResult result = dispatcher.dispatch(
            conversation(), "你好", new ReplyTarget("staff-1", "cid-group", "2", ""));

        assertTrue(result.delivered());
        verify(dingTalkClient).sendRobotMessageToGroup(
            "app-key", "app-secret", "robot-code", "cid-group", "你好");
    }

    @Test
    void sendsHumanMarkdownBackToOriginalDingTalkGroup() {
        when(channelConfigMapper.selectOne(any(Wrapper.class))).thenReturn(config());
        when(dingTalkClient.sendRobotMarkdownToGroup(
            "app-key", "app-secret", "robot-code", "cid-group",
            "客服回复", "请参考下图\n\n![操作图](https://bot.example.com/image.png)"))
            .thenReturn(true);

        ChannelReplyDispatcher.DispatchResult result = dispatcher.dispatchMarkdown(
            conversation(), "客服回复",
            "请参考下图\n\n![操作图](https://bot.example.com/image.png)",
            new ReplyTarget("staff-1", "cid-group", "2", ""));

        assertTrue(result.delivered());
        verify(dingTalkClient).sendRobotMarkdownToGroup(
            "app-key", "app-secret", "robot-code", "cid-group",
            "客服回复", "请参考下图\n\n![操作图](https://bot.example.com/image.png)");
    }

    private BotChannelConfig config() {
        BotChannelConfig config = new BotChannelConfig();
        config.setId(5L);
        config.setChannelType("dingtalk");
        config.setStatus(1);
        config.setConfigJson("{\"clientId\":\"app-key\","
            + "\"clientSecret\":\"app-secret\",\"robotCode\":\"robot-code\"}");
        return config;
    }

    private BotConversation conversation() {
        BotConversation conversation = new BotConversation();
        conversation.setChannelType("dingtalk");
        conversation.setChannelUserId("staff-1");
        return conversation;
    }
}
