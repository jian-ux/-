package com.feisheng.bot.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.gateway.dto.ChannelMessageDTO;
import com.feisheng.bot.gateway.dto.DingTalkMediaRequest;
import com.feisheng.bot.gateway.service.DingTalkMediaProcessingException;
import com.feisheng.bot.gateway.service.DingTalkMediaProcessor;
import com.feisheng.bot.gateway.service.impl.ChannelServiceImpl;
import com.feisheng.bot.gateway.stream.DingTalkStreamCallbackListener;
import com.feisheng.bot.gateway.util.DingTalkCryptoUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DingTalkControllerTest {
    private static final String SECRET = "test-secret";

    @Test
    void returnsDingTalkTextResponseForIncomingTextMessage() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        when(channelService.processMessage(any())).thenReturn(Map.of("reply", "您好，我是智能客服"));
        DingTalkController controller = controller(channelService, SECRET);
        HttpServletRequest request = signedRequest(SECRET);

        Map<String, Object> body = messageBody("你好");
        ResponseEntity<Map<String, Object>> response = controller.receiveMessage(body, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("msgtype", "text", "text", Map.of("content", "您好，我是智能客服")), response.getBody());
        ArgumentCaptor<ChannelMessageDTO> message = ArgumentCaptor.forClass(ChannelMessageDTO.class);
        verify(channelService).processMessage(message.capture());
        assertEquals("张三", message.getValue().getSenderName());
    }

    @Test
    void returnsTextFallbackForEmptyMessage() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkController controller = controller(channelService, SECRET);
        Map<String, Object> body = messageBody("   ");

        ResponseEntity<Map<String, Object>> response = controller.receiveMessage(body, signedRequest(SECRET));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("msgtype", "text", "text", Map.of("content", "请发送文本内容，我会尽力帮您解答。")), response.getBody());
        verify(channelService, never()).processMessage(any());
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkController controller = controller(channelService, SECRET);

        ResponseEntity<Map<String, Object>> response = controller.receiveMessage(
            messageBody("你好"), signedRequest("other-secret"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(channelService, never()).processMessage(any());
    }

    @Test
    void rejectsOutgoingRobotWhenSecretIsMissing() {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkController controller = controller(channelService, "");

        ResponseEntity<Map<String, Object>> response = controller.receiveMessage(
            messageBody("你好"), mock(HttpServletRequest.class));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        verify(channelService, never()).processMessage(any());
    }

    @Test
    void silentlyAcknowledgesDuplicateMessage() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        when(channelService.processMessage(any())).thenReturn(Map.of("duplicate", true));
        DingTalkController controller = controller(channelService, SECRET);

        ResponseEntity<Map<String, Object>> response = controller.receiveMessage(
            messageBody("你好"), signedRequest(SECRET));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of(), response.getBody());
    }

    @Test
    void returnsMarkdownResponseForKnowledgeImage() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        when(channelService.processMessage(any())).thenReturn(Map.of(
            "reply", "产品介绍",
            "attachments", List.of(Map.of(
                "type", "image", "documentId", 42L, "title", "产品图",
                "url", "https://bot.example.com/image/42"))));
        DingTalkController controller = controller(channelService, SECRET);

        ResponseEntity<Map<String, Object>> response = controller.receiveMessage(
            messageBody("介绍产品"), signedRequest(SECRET));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of(
            "msgtype", "markdown",
            "markdown", Map.of(
                "title", "智能客服回复",
                "text", "产品介绍\n\n![产品图](https://bot.example.com/image/42)")),
            response.getBody());
    }

    @Test
    void returnsMarkdownResponseWhenCoreSuppliesRichReply() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        when(channelService.processMessage(any())).thenReturn(Map.of(
            "reply", "结论\n- 已确认",
            "richReply", "**结论**\n\n- 已确认"));
        DingTalkController controller = controller(channelService, SECRET);

        ResponseEntity<Map<String, Object>> response = controller.receiveMessage(
            messageBody("咨询流程"), signedRequest(SECRET));

        assertEquals(Map.of(
            "msgtype", "markdown",
            "markdown", Map.of(
                "title", "智能客服回复",
                "text", "**结论**\n\n- 已确认")), response.getBody());
    }

    @Test
    void dispatchesHttpTextAsynchronouslyWhenSessionWebhookIsPresent() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkStreamCallbackListener streamListener =
            mock(DingTalkStreamCallbackListener.class);
        when(streamListener.dispatchText(any(), anyString())).thenReturn(true);
        DingTalkController controller = new DingTalkController(
            channelService, new ObjectMapper(), (DingTalkMediaProcessor) null, streamListener,
            SECRET, "", "");
        Map<String, Object> body = messageBody("慢问题");
        body.put("sessionWebhook", "https://oapi.dingtalk.com/robot/sendBySession");

        ResponseEntity<Map<String, Object>> response = controller.receiveMessage(
            body, signedRequest(SECRET));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of(), response.getBody());
        ArgumentCaptor<ChannelMessageDTO> dto = ArgumentCaptor.forClass(ChannelMessageDTO.class);
        verify(streamListener).dispatchText(dto.capture(),
            eq("https://oapi.dingtalk.com/robot/sendBySession"));
        assertEquals("text", dto.getValue().getMsgType());
        assertEquals("慢问题", dto.getValue().getContent());
        verifyNoInteractions(channelService);
    }

    @Test
    void dispatchesHttpPictureAsynchronouslyWhenSessionWebhookIsPresent() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkMediaProcessor mediaProcessor = mock(DingTalkMediaProcessor.class);
        DingTalkStreamCallbackListener streamListener =
            mock(DingTalkStreamCallbackListener.class);
        when(streamListener.dispatchMedia(any(), any(), anyString())).thenReturn(true);
        DingTalkController controller = new DingTalkController(
            channelService, new ObjectMapper(), mediaProcessor, streamListener,
            SECRET, "", "");
        Map<String, Object> body = mediaBody("picture", Map.of(
            "pictureDownloadCode", "picture-code", "downloadCode", "download-code",
            "fileName", "order.png"));
        body.put("sessionWebhook", "https://oapi.dingtalk.com/robot/sendBySession");

        ResponseEntity<Map<String, Object>> response = controller.receiveMessage(
            body, signedRequest(SECRET));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of(), response.getBody());
        ArgumentCaptor<ChannelMessageDTO> dto = ArgumentCaptor.forClass(ChannelMessageDTO.class);
        ArgumentCaptor<DingTalkMediaRequest> media =
            ArgumentCaptor.forClass(DingTalkMediaRequest.class);
        verify(streamListener).dispatchMedia(dto.capture(), media.capture(),
            eq("https://oapi.dingtalk.com/robot/sendBySession"));
        assertEquals("picture", dto.getValue().getMsgType());
        assertEquals("download-code", media.getValue().downloadCode());
        assertEquals("order.png", media.getValue().fileName());
        verifyNoInteractions(mediaProcessor);
    }

    @Test
    void dispatchesPictureNestedInHttpRichTextCallback() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkMediaProcessor mediaProcessor = mock(DingTalkMediaProcessor.class);
        DingTalkStreamCallbackListener streamListener =
            mock(DingTalkStreamCallbackListener.class);
        when(streamListener.dispatchMedia(any(), any(), anyString())).thenReturn(true);
        DingTalkController controller = new DingTalkController(
            channelService, new ObjectMapper(), mediaProcessor, streamListener,
            SECRET, "", "");
        Map<String, Object> body = mediaBody("richText", Map.of(
            "richText", List.of(
                Map.of("type", "text", "text", "@智能客服"),
                Map.of("type", "text", "text", "这个怎么操作"),
                Map.of("type", "picture",
                    "pictureDownloadCode", "rich-picture-code",
                    "downloadCode", "rich-download-code"))));
        body.put("robotCode", "robot-code");
        body.put("sessionWebhook", "https://oapi.dingtalk.com/robot/sendBySession");

        ResponseEntity<Map<String, Object>> response = controller.receiveMessage(
            body, signedRequest(SECRET));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<DingTalkMediaRequest> media =
            ArgumentCaptor.forClass(DingTalkMediaRequest.class);
        verify(streamListener).dispatchMedia(any(), media.capture(), anyString());
        assertEquals("picture", media.getValue().msgType());
        assertEquals("rich-download-code", media.getValue().downloadCode());
        assertEquals("robot-code", media.getValue().robotCode());
        assertEquals("@智能客服\n这个怎么操作", media.getValue().caption());
        verifyNoInteractions(mediaProcessor);
    }

    @Test
    void synchronouslyNormalizesVoiceWhenHttpCallbackHasNoSessionWebhook() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkMediaProcessor mediaProcessor = mock(DingTalkMediaProcessor.class);
        when(mediaProcessor.normalize(any())).thenReturn("voice text");
        when(channelService.processMessage(any(ChannelMessageDTO.class), any()))
            .thenAnswer(invocation -> {
                Supplier<String> content = invocation.getArgument(1);
                assertEquals("voice text", content.get());
                return Map.of("reply", "语音问题已收到");
            });
        DingTalkController controller = new DingTalkController(
            channelService, new ObjectMapper(), mediaProcessor, null,
            SECRET, "", "");
        Map<String, Object> body = mediaBody("audio", Map.of(
            "downloadCode", "audio-code", "recognition", "查询订单"));

        ResponseEntity<Map<String, Object>> response = controller.receiveMessage(
            body, signedRequest(SECRET));

        assertEquals(Map.of("msgtype", "text",
            "text", Map.of("content", "语音问题已收到")), response.getBody());
        ArgumentCaptor<DingTalkMediaRequest> media =
            ArgumentCaptor.forClass(DingTalkMediaRequest.class);
        verify(mediaProcessor).normalize(media.capture());
        assertEquals("audio-code", media.getValue().downloadCode());
        assertEquals("查询订单", media.getValue().recognition());
    }

    @Test
    void returnsSafeMediaFailureToHttpCallback() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkMediaProcessor mediaProcessor = mock(DingTalkMediaProcessor.class);
        when(mediaProcessor.normalize(any())).thenThrow(
            new DingTalkMediaProcessingException("媒体文件下载失败，请重新发送"));
        when(channelService.processMessage(any(ChannelMessageDTO.class), any()))
            .thenAnswer(invocation -> {
                Supplier<String> content = invocation.getArgument(1);
                content.get();
                return Map.of();
            });
        DingTalkController controller = new DingTalkController(
            channelService, new ObjectMapper(), mediaProcessor, null,
            SECRET, "", "");

        ResponseEntity<Map<String, Object>> response = controller.receiveMessage(
            mediaBody("picture", Map.of("downloadCode", "expired-code")),
            signedRequest(SECRET));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("msgtype", "text",
            "text", Map.of("content", "媒体文件下载失败，请重新发送")), response.getBody());
    }

    private DingTalkController controller(ChannelServiceImpl service, String secret) {
        return new DingTalkController(service, new ObjectMapper(), secret, "", "");
    }

    private HttpServletRequest signedRequest(String signingSecret) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("timestamp")).thenReturn(timestamp);
        when(request.getHeader("sign")).thenReturn(
            DingTalkCryptoUtil.computeSignature(timestamp, signingSecret));
        return request;
    }

    private Map<String, Object> messageBody(String content) {
        Map<String, Object> body = new HashMap<>();
        body.put("conversationId", "cid-1");
        body.put("msgId", "msg-1");
        body.put("senderStaffId", "user-1");
        body.put("senderNick", "张三");
        body.put("msgtype", "text");
        body.put("text", Map.of("content", content));
        return body;
    }

    private Map<String, Object> mediaBody(String msgType, Map<String, Object> content) {
        Map<String, Object> body = new HashMap<>();
        body.put("conversationId", "cid-1");
        body.put("msgId", "msg-media-1");
        body.put("senderStaffId", "user-1");
        body.put("senderNick", "张三");
        body.put("msgtype", msgType);
        body.put("content", content);
        return body;
    }
}
