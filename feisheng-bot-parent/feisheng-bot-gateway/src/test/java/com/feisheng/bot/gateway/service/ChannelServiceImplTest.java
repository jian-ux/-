package com.feisheng.bot.gateway.service;

import com.feisheng.bot.common.util.RedisUtil;
import com.feisheng.bot.gateway.client.CoreClient;
import com.feisheng.bot.gateway.dto.ChannelMessageDTO;
import com.feisheng.bot.gateway.service.impl.ChannelServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelServiceImplTest {
    @Test
    void reservesMessageIdBeforeResolvingExpensiveMediaContent() {
        CoreClient coreClient = mock(CoreClient.class);
        RedisUtil redisUtil = mock(RedisUtil.class);
        ChannelUserProfileService profileService = mock(ChannelUserProfileService.class);
        when(redisUtil.setnx("msg:dedup:dingtalk:msg-1", "processing",
            24, TimeUnit.HOURS)).thenReturn(false);
        AtomicInteger normalizations = new AtomicInteger();
        ChannelServiceImpl service = new ChannelServiceImpl(
            coreClient, redisUtil, profileService);

        Map<String, Object> result = service.processMessage(
            mediaMessage(), () -> {
                normalizations.incrementAndGet();
                return "recognized text";
            });

        assertTrue(Boolean.TRUE.equals(result.get("duplicate")));
        assertEquals(0, normalizations.get());
        verify(coreClient, never()).sendMessage(any(), any(), any(), any());
    }

    @Test
    void normalizesReservedMediaThenUsesExistingDialogPipeline() {
        CoreClient coreClient = mock(CoreClient.class);
        RedisUtil redisUtil = mock(RedisUtil.class);
        ChannelUserProfileService profileService = mock(ChannelUserProfileService.class);
        when(redisUtil.setnx("msg:dedup:dingtalk:msg-1", "processing",
            24, TimeUnit.HOURS)).thenReturn(true);
        when(coreClient.sendMessage(
            "dingtalk", "user-1", "recognized text", null))
            .thenReturn(Map.of("reply", "answer"));
        ChannelServiceImpl service = new ChannelServiceImpl(
            coreClient, redisUtil, profileService);
        ChannelMessageDTO message = mediaMessage();

        Map<String, Object> result = service.processMessage(
            message, () -> "recognized text");

        assertEquals("answer", result.get("reply"));
        assertEquals("recognized text", message.getContent());
        verify(redisUtil).setex(
            "msg:dedup:dingtalk:msg-1", "done", 24, TimeUnit.HOURS);
        verify(profileService).refreshConversationStats(eq(message));
    }

    private ChannelMessageDTO mediaMessage() {
        ChannelMessageDTO dto = new ChannelMessageDTO();
        dto.setChannelType("dingtalk");
        dto.setChannelUserId("user-1");
        dto.setMsgId("msg-1");
        dto.setMsgType("picture");
        return dto;
    }
}
