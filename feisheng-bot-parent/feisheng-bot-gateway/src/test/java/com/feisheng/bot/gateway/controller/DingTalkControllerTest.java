package com.feisheng.bot.gateway.controller;

import com.feisheng.bot.gateway.service.impl.ChannelServiceImpl;
import com.feisheng.bot.gateway.util.DingTalkCryptoUtil;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DingTalkControllerTest {
    @Test
    void returnsDingTalkTextResponseForIncomingTextMessage() {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        when(channelService.processMessage(any())).thenReturn(Map.of("reply", "您好，我是智能客服"));
        DingTalkController controller = new DingTalkController(channelService, "");

        Map<String, Object> body = new HashMap<>();
        body.put("conversationId", "cid-1");
        body.put("msgId", "msg-1");
        body.put("senderStaffId", "user-1");
        body.put("msgtype", "text");
        body.put("text", Map.of("content", "你好"));

        ResponseEntity<Object> response = controller.receiveMessage(body, "", "");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("msgtype", "text", "text", Map.of("content", "您好，我是智能客服")), response.getBody());
    }

    @Test
    void returnsTextFallbackForEmptyMessage() {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkController controller = new DingTalkController(channelService, "");

        Map<String, Object> body = new HashMap<>();
        body.put("conversationId", "cid-1");
        body.put("msgId", "msg-1");
        body.put("senderStaffId", "user-1");
        body.put("text", Map.of("content", "   "));

        ResponseEntity<Object> response = controller.receiveMessage(body, "", "");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("msgtype", "text", "text", Map.of("content", "请发送文本内容，我会尽力帮您解答。")), response.getBody());
        verify(channelService, never()).processMessage(any());
    }

    @Test
    void rejectsInvalidSignatureWhenSecretIsConfigured() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkController controller = new DingTalkController(channelService, "test-secret");

        Map<String, Object> body = Map.of("text", Map.of("content", "你好"));
        String timestamp = "1720588800000";
        String wrongSign = DingTalkCryptoUtil.computeSignature(timestamp, "other-secret");

        ResponseEntity<Object> response = controller.receiveMessage(body, timestamp, wrongSign);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(channelService, never()).processMessage(any());
    }

    @Test
    void returnsTextResponseForDuplicateMessage() {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        when(channelService.processMessage(any())).thenReturn(Map.of("duplicate", true));
        DingTalkController controller = new DingTalkController(channelService, "");

        Map<String, Object> body = new HashMap<>();
        body.put("conversationId", "cid-1");
        body.put("msgId", "msg-dup");
        body.put("senderStaffId", "user-1");
        body.put("text", Map.of("content", "你好"));

        ResponseEntity<Object> response = controller.receiveMessage(body, "", "");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("msgtype", "text", "text", Map.of("content", "消息已处理，无需重复发送。")), response.getBody());
    }

    @Test
    void returnsFallbackTextWhenExceptionOccurs() {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        when(channelService.processMessage(any())).thenThrow(new RuntimeException("boom"));
        DingTalkController controller = new DingTalkController(channelService, "");

        Map<String, Object> body = new HashMap<>();
        body.put("conversationId", "cid-1");
        body.put("msgId", "msg-err");
        body.put("senderStaffId", "user-1");
        body.put("text", Map.of("content", "你好"));

        ResponseEntity<Object> response = controller.receiveMessage(body, "", "");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("msgtype", "text", "text", Map.of("content", "服务暂时不可用，请稍后再试。")), response.getBody());
    }

    @Test
    void fallsBackToContentContentWhenTextContentAbsent() {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        when(channelService.processMessage(any())).thenReturn(Map.of("reply", "OK"));
        DingTalkController controller = new DingTalkController(channelService, "");

        Map<String, Object> body = new HashMap<>();
        body.put("conversationId", "cid-1");
        body.put("msgId", "msg-2");
        body.put("senderStaffId", "user-1");
        body.put("content", Map.of("content", "fallback-text"));

        ResponseEntity<Object> response = controller.receiveMessage(body, "", "");
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void fallsBackToSenderIdWhenSenderStaffIdAbsent() {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        when(channelService.processMessage(any())).thenReturn(Map.of("reply", "OK"));
        DingTalkController controller = new DingTalkController(channelService, "");

        Map<String, Object> body = new HashMap<>();
        body.put("senderId", "sender-only");
        body.put("text", Map.of("content", "hello"));

        ResponseEntity<Object> response = controller.receiveMessage(body, "", "");
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}