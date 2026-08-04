package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotChannelConfig;
import com.feisheng.bot.admin.entity.BotConversation;
import com.feisheng.bot.admin.mapper.BotChannelConfigMapper;
import com.feisheng.bot.gateway.client.DingTalkClient;
import com.feisheng.bot.gateway.client.WeChatWorkClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
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
