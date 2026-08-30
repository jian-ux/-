package com.feisheng.bot.gateway.stream;

import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;
import com.feisheng.bot.gateway.dto.ChannelMessageDTO;
import com.feisheng.bot.gateway.dto.DingTalkMediaRequest;
import com.feisheng.bot.gateway.service.DingTalkMediaProcessor;
import com.feisheng.bot.gateway.service.DingTalkImageReplyDispatcher;
import com.feisheng.bot.gateway.service.impl.ChannelServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DingTalkStreamCallbackListenerTest {

    @Test
    void sendsAiReplyToOriginalSessionWebhook() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkStreamReplySender replySender = mock(DingTalkStreamReplySender.class);
        when(channelService.processMessage(any())).thenReturn(Map.of("reply", "连接测试成功"));
        DingTalkStreamCallbackListener listener = new DingTalkStreamCallbackListener(
            channelService, replySender);

        Map<String, Object> ack = listener.execute(message("你好"));

        assertTrue(ack.isEmpty());
        verify(replySender).replyText("https://oapi.dingtalk.com/robot/sendBySession", "连接测试成功");
        ArgumentCaptor<ChannelMessageDTO> dto = ArgumentCaptor.forClass(ChannelMessageDTO.class);
        verify(channelService).processMessage(dto.capture());
        assertEquals("dingtalk", dto.getValue().getChannelType());
        assertEquals("user-1", dto.getValue().getChannelUserId());
        assertEquals("张三", dto.getValue().getSenderName());
        assertEquals("msg-1", dto.getValue().getMsgId());
        assertEquals("你好", dto.getValue().getContent());
    }

    @Test
    void doesNotSendWhenCoreSuppressesHumanHandlingReply() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkStreamReplySender replySender = mock(DingTalkStreamReplySender.class);
        when(channelService.processMessage(any())).thenReturn(
            Map.of("reply", "", "suppressReply", true));
        DingTalkStreamCallbackListener listener = new DingTalkStreamCallbackListener(
            channelService, replySender);

        listener.execute(message("人工接管后的补充消息"));

        verify(replySender, never()).replyText(any(), any());
        verify(replySender, never()).replyMarkdown(any(), any(), any());
    }

    @Test
    void acknowledgesTextBeforeRunningAiProcessing() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkStreamReplySender replySender = mock(DingTalkStreamReplySender.class);
        when(channelService.processMessage(any())).thenReturn(Map.of("reply", "异步回复"));
        AtomicReference<Runnable> pending = new AtomicReference<>();
        DingTalkStreamCallbackListener listener = new DingTalkStreamCallbackListener(
            channelService, replySender, pending::set);

        Map<String, Object> ack = listener.execute(message("慢问题"));

        assertTrue(ack.isEmpty());
        assertNotNull(pending.get());
        verify(channelService, never()).processMessage(any());
        verifyNoInteractions(replySender);

        pending.get().run();

        verify(channelService).processMessage(any());
        verify(replySender).replyText(
            "https://oapi.dingtalk.com/robot/sendBySession", "异步回复");
    }

    @Test
    void sendsProcessingAcknowledgementBeforeComplexQuestionCompletes() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkStreamReplySender replySender = mock(DingTalkStreamReplySender.class);
        when(channelService.processMessage(any())).thenReturn(Map.of("reply", "完整分析结果"));
        AtomicReference<Runnable> pending = new AtomicReference<>();
        DingTalkStreamCallbackListener listener = new DingTalkStreamCallbackListener(
            channelService, replySender, pending::set);
        String question = "请结合电子合同签署、实名认证、证据存证和争议处理，"
            + "详细分析企业上线前需要完成哪些准备工作，并分别说明风险和建议。";

        listener.execute(message(question));

        verify(replySender).replyText(
            "https://oapi.dingtalk.com/robot/sendBySession",
            "这个问题需要进一步检索和分析，我正在处理中，稍后发送完整答复。");
        verify(channelService, never()).processMessage(any());
        assertNotNull(pending.get());

        pending.get().run();

        verify(channelService).processMessage(any());
        verify(replySender).replyText(
            "https://oapi.dingtalk.com/robot/sendBySession", "完整分析结果");
    }

    @Test
    void repliesWhenMediaProcessorIsNotAvailable() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkStreamReplySender replySender = mock(DingTalkStreamReplySender.class);
        when(channelService.processMessage(any(ChannelMessageDTO.class), any()))
            .thenAnswer(invocation -> {
                Supplier<String> content = invocation.getArgument(1);
                content.get();
                return Map.of();
            });
        DingTalkStreamCallbackListener listener = new DingTalkStreamCallbackListener(
            channelService, replySender);
        ChatbotMessage message = message(null);
        message.setMsgtype("picture");

        listener.execute(message);

        verify(channelService, never()).processMessage(any());
        verify(replySender).replyText(
            "https://oapi.dingtalk.com/robot/sendBySession",
            "当前未启用图片或语音识别，请改用文字发送。");
    }

    @Test
    void normalizesPictureAsynchronouslyThenUsesExistingMessagePipeline() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkStreamReplySender replySender = mock(DingTalkStreamReplySender.class);
        DingTalkMediaProcessor mediaProcessor = mock(DingTalkMediaProcessor.class);
        when(mediaProcessor.normalize(any())).thenReturn(
            "[客户发送了一张图片，以下为图片中的文字]\n订单已支付");
        when(channelService.processMessage(any(ChannelMessageDTO.class), any()))
            .thenAnswer(invocation -> {
                ChannelMessageDTO dto = invocation.getArgument(0);
                Supplier<String> content = invocation.getArgument(1);
                dto.setContent(content.get());
                return Map.of("reply", "这笔订单已支付。");
            });
        DingTalkStreamCallbackListener listener = new DingTalkStreamCallbackListener(
            channelService, replySender, mediaProcessor, Runnable::run);
        ChatbotMessage message = message(null);
        message.setMsgtype("picture");
        MessageContent picture = new MessageContent();
        picture.setPictureDownloadCode("picture-code");
        picture.setDownloadCode("download-code");
        picture.setFileName("order.png");
        message.setContent(picture);

        listener.execute(message);

        ArgumentCaptor<DingTalkMediaRequest> media =
            ArgumentCaptor.forClass(DingTalkMediaRequest.class);
        verify(mediaProcessor).normalize(media.capture());
        assertEquals("picture", media.getValue().msgType());
        assertEquals("download-code", media.getValue().downloadCode());
        assertEquals("order.png", media.getValue().fileName());
        verify(replySender).replyText(
            "https://oapi.dingtalk.com/robot/sendBySession", "这笔订单已支付。");
    }

    @Test
    void extractsPictureFromRichTextCreatedByGroupMention() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkStreamReplySender replySender = mock(DingTalkStreamReplySender.class);
        DingTalkMediaProcessor mediaProcessor = mock(DingTalkMediaProcessor.class);
        when(mediaProcessor.normalize(any())).thenReturn("rich text picture OCR");
        when(channelService.processMessage(any(ChannelMessageDTO.class), any()))
            .thenAnswer(invocation -> {
                Supplier<String> content = invocation.getArgument(1);
                assertEquals("rich text picture OCR", content.get());
                return Map.of("reply", "图片已识别");
            });
        DingTalkStreamCallbackListener listener = new DingTalkStreamCallbackListener(
            channelService, replySender, mediaProcessor, Runnable::run);
        ChatbotMessage message = message(null);
        message.setMsgtype("richText");
        MessageContent mention = new MessageContent();
        mention.setType("text");
        mention.setText("@智能客服");
        MessageContent question = new MessageContent();
        question.setType("text");
        question.setText("这个怎么操作");
        MessageContent picture = new MessageContent();
        picture.setType("picture");
        picture.setPictureDownloadCode("rich-picture-code");
        picture.setDownloadCode("rich-download-code");
        MessageContent richText = new MessageContent();
        richText.setRichText(List.of(mention, question, picture));
        message.setContent(richText);

        listener.execute(message);

        ArgumentCaptor<DingTalkMediaRequest> media =
            ArgumentCaptor.forClass(DingTalkMediaRequest.class);
        verify(mediaProcessor).normalize(media.capture());
        assertEquals("picture", media.getValue().msgType());
        assertEquals("rich-download-code", media.getValue().downloadCode());
        assertEquals("@智能客服\n这个怎么操作", media.getValue().caption());
        verify(replySender).replyText(
            "https://oapi.dingtalk.com/robot/sendBySession", "图片已识别");
    }

    @Test
    void passesDingTalkVoiceRecognitionToMediaProcessor() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkStreamReplySender replySender = mock(DingTalkStreamReplySender.class);
        DingTalkMediaProcessor mediaProcessor = mock(DingTalkMediaProcessor.class);
        when(mediaProcessor.normalize(any())).thenReturn("voice text");
        when(channelService.processMessage(any(ChannelMessageDTO.class), any()))
            .thenAnswer(invocation -> {
                Supplier<String> content = invocation.getArgument(1);
                content.get();
                return Map.of("reply", "voice answer");
            });
        DingTalkStreamCallbackListener listener = new DingTalkStreamCallbackListener(
            channelService, replySender, mediaProcessor, Runnable::run);
        ChatbotMessage message = message(null);
        message.setMsgtype("audio");
        MessageContent audio = new MessageContent();
        audio.setDownloadCode("audio-code");
        audio.setRecognition("查询订单进度");
        message.setContent(audio);

        listener.execute(message);

        ArgumentCaptor<DingTalkMediaRequest> media =
            ArgumentCaptor.forClass(DingTalkMediaRequest.class);
        verify(mediaProcessor).normalize(media.capture());
        assertEquals("audio-code", media.getValue().downloadCode());
        assertEquals("查询订单进度", media.getValue().recognition());
        verify(replySender).replyText(
            "https://oapi.dingtalk.com/robot/sendBySession", "voice answer");
    }

    @Test
    void repliesWithBusyMessageWhenMediaQueueIsFull() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkStreamReplySender replySender = mock(DingTalkStreamReplySender.class);
        DingTalkMediaProcessor mediaProcessor = mock(DingTalkMediaProcessor.class);
        Executor rejected = command -> {
            throw new java.util.concurrent.RejectedExecutionException("full");
        };
        DingTalkStreamCallbackListener listener = new DingTalkStreamCallbackListener(
            channelService, replySender, mediaProcessor, rejected);
        ChatbotMessage message = message(null);
        message.setMsgtype("picture");
        MessageContent picture = new MessageContent();
        picture.setPictureDownloadCode("picture-code");
        message.setContent(picture);

        listener.execute(message);

        verify(channelService, never()).processMessage(any(ChannelMessageDTO.class), any());
        verify(replySender).replyText(
            "https://oapi.dingtalk.com/robot/sendBySession",
            "当前图片或语音识别任务较多，请稍后重试。");
    }

    @Test
    void repliesWithBusyMessageWhenTextQueueIsFull() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkStreamReplySender replySender = mock(DingTalkStreamReplySender.class);
        Executor rejected = command -> {
            throw new java.util.concurrent.RejectedExecutionException("full");
        };
        DingTalkStreamCallbackListener listener = new DingTalkStreamCallbackListener(
            channelService, replySender, rejected);

        listener.execute(message("排队问题"));

        verify(channelService, never()).processMessage(any());
        verify(replySender).replyText(
            "https://oapi.dingtalk.com/robot/sendBySession", "当前咨询较多，请稍后重试。");
    }

    @Test
    void silentlyAcknowledgesDuplicateMessage() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkStreamReplySender replySender = mock(DingTalkStreamReplySender.class);
        when(channelService.processMessage(any())).thenReturn(Map.of("duplicate", true));
        DingTalkStreamCallbackListener listener = new DingTalkStreamCallbackListener(
            channelService, replySender);

        listener.execute(message("重复消息"));

        verify(channelService).processMessage(any());
        verifyNoInteractions(replySender);
    }

    @Test
    void embedsPublicKnowledgeImageInMarkdownWithoutSecondDispatch() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkStreamReplySender replySender = mock(DingTalkStreamReplySender.class);
        DingTalkImageReplyDispatcher imageDispatcher =
            mock(DingTalkImageReplyDispatcher.class);
        Map<String, Object> result = Map.of(
            "reply", "这是点签产品介绍。",
            "attachments", List.of(Map.of(
                "type", "image",
                "documentId", 42L,
                "title", "点签产品图",
                "url", "https://bot.example.com/api/public/knowledge-images/42?signature=test")));
        when(channelService.processMessage(any())).thenReturn(result);
        DingTalkStreamCallbackListener listener = new DingTalkStreamCallbackListener(
            channelService, replySender, imageDispatcher);
        ChatbotMessage message = message("介绍一下产品");
        message.setConversationType("2");

        listener.execute(message);

        verify(replySender).replyMarkdown(
            "https://oapi.dingtalk.com/robot/sendBySession",
            "智能客服回复",
            "这是点签产品介绍。\n\n![点签产品图]"
                + "(https://bot.example.com/api/public/knowledge-images/42?signature=test)");
        verify(replySender, never()).replyText(any(), any());
        verifyNoInteractions(imageDispatcher);
    }

    @Test
    void sendsRichReplyAsMarkdownWithoutChangingPlainReplyContract() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkStreamReplySender replySender = mock(DingTalkStreamReplySender.class);
        when(channelService.processMessage(any())).thenReturn(Map.of(
            "reply", "结论\n- 已确认",
            "richReply", "**结论**\n\n- 已确认"));
        DingTalkStreamCallbackListener listener = new DingTalkStreamCallbackListener(
            channelService, replySender);

        listener.execute(message("咨询流程"));

        verify(replySender).replyMarkdown(
            "https://oapi.dingtalk.com/robot/sendBySession",
            "智能客服回复", "**结论**\n\n- 已确认");
        verify(replySender, never()).replyText(any(), any());
    }

    @Test
    void sendsFallbackWhenAiProcessingFails() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        DingTalkStreamReplySender replySender = mock(DingTalkStreamReplySender.class);
        when(channelService.processMessage(any())).thenThrow(new RuntimeException("boom"));
        DingTalkStreamCallbackListener listener = new DingTalkStreamCallbackListener(
            channelService, replySender);

        listener.execute(message("你好"));

        verify(replySender).replyText(
            "https://oapi.dingtalk.com/robot/sendBySession", "服务暂时不可用，请稍后再试。");
    }

    private static ChatbotMessage message(String content) {
        ChatbotMessage message = new ChatbotMessage();
        message.setConversationId("cid-1");
        message.setMsgId("msg-1");
        message.setSenderStaffId("user-1");
        message.setSenderNick("张三");
        message.setMsgtype("text");
        message.setSessionWebhook("https://oapi.dingtalk.com/robot/sendBySession");
        message.setCreateAt(1720588800000L);
        if (content != null) {
            MessageContent text = new MessageContent();
            text.setContent(content);
            message.setText(text);
        }
        return message;
    }
}
