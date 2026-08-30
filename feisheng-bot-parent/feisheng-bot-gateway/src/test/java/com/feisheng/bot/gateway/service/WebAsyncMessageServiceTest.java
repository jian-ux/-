package com.feisheng.bot.gateway.service;

import com.feisheng.bot.gateway.dto.ChannelMessageDTO;
import com.feisheng.bot.gateway.service.impl.ChannelServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebAsyncMessageServiceTest {

    @Test
    void exposesProcessingThenCompletedResult() {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        when(channelService.processMessage(any())).thenReturn(Map.of("reply", "异步答复"));
        AtomicReference<Runnable> pending = new AtomicReference<>();
        WebAsyncMessageService service = new WebAsyncMessageService(
            channelService, pending::set, 600, 100);

        Map<String, Object> submitted = service.submit(message());
        String requestId = submitted.get("requestId").toString();

        assertEquals("processing", submitted.get("status"));
        assertNotNull(pending.get());
        verify(channelService, never()).processMessage(any());

        pending.get().run();
        Map<String, Object> completed = service.status(requestId);

        assertEquals("completed", completed.get("status"));
        assertEquals(Map.of("reply", "异步答复"), completed.get("result"));
        verify(channelService).processMessage(any());
    }

    @Test
    void forcesWebChannelBeforeProcessing() {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        when(channelService.processMessage(any())).thenAnswer(invocation -> {
            ChannelMessageDTO dto = invocation.getArgument(0);
            return Map.of("channelType", dto.getChannelType());
        });
        WebAsyncMessageService service = new WebAsyncMessageService(
            channelService, Runnable::run, 600, 100);

        Map<String, Object> submitted = service.submit(message());
        Map<String, Object> completed = service.status(submitted.get("requestId").toString());

        assertEquals(Map.of("channelType", "web"), completed.get("result"));
    }

    @Test
    void exposesBusinessErrorAsFailedAndKeepsResult() {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        Map<String, Object> error = Map.of(
            "source", "error", "answerStatus", "error", "reply", "请稍后重试");
        when(channelService.processMessage(any())).thenReturn(error);
        WebAsyncMessageService service = new WebAsyncMessageService(
            channelService, Runnable::run, 600, 100);

        Map<String, Object> submitted = service.submit(message());
        Map<String, Object> failed = service.status(submitted.get("requestId").toString());

        assertEquals("failed", failed.get("status"));
        assertEquals(error, failed.get("result"));
    }

    private ChannelMessageDTO message() {
        ChannelMessageDTO dto = new ChannelMessageDTO();
        dto.setChannelUserId("web-user-1");
        dto.setMsgId("web-msg-1");
        dto.setContent("请详细分析电子合同上线方案");
        return dto;
    }
}
