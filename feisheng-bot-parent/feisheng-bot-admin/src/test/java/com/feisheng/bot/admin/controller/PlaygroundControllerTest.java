package com.feisheng.bot.admin.controller;

import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import com.feisheng.bot.admin.mapper.BotAiModelConfigMapper;
import com.feisheng.bot.admin.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.admin.service.ImageOcrService;
import com.feisheng.bot.admin.service.SpeechTranscriptionService;
import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.core.service.SpeechSynthesisService;
import com.feisheng.bot.core.service.impl.DialogServiceImpl;
import com.feisheng.bot.knowledge.service.MinioStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaygroundControllerTest {
    @Mock private BotAiModelConfigMapper modelMapper;
    @Mock private BotKnowledgeDocumentMapper documentMapper;
    @Mock private DialogServiceImpl dialogService;
    @Mock private ImageOcrService imageOcrService;
    @Mock private SpeechTranscriptionService transcriptionService;
    @Mock private SpeechSynthesisService synthesisService;
    @Mock private MinioStorageService storageService;

    private PlaygroundController controller;

    @BeforeEach
    void setUp() {
        controller = new PlaygroundController(modelMapper, documentMapper, dialogService,
            imageOcrService, transcriptionService, synthesisService, storageService, 24);
    }

    @Test
    void screenshotChatCombinesOcrWithGlobalRetrieval() {
        BotKnowledgeDocument image = new BotKnowledgeDocument();
        image.setId(12L);
        image.setTitle("订单截图.png");
        image.setMediaType("IMAGE");
        image.setOcrStatus("COMPLETED");
        image.setOcrText("订单状态：支付失败");
        image.setExpiresAt(new Date(System.currentTimeMillis() + 60_000));
        when(documentMapper.selectById(12L)).thenReturn(image);
        when(dialogService.sendWithMultimodalContext(
                eq("playground"), eq("trial-123"), eq("这个怎么处理"), eq("截图问答"),
                contains("订单状态：支付失败"), anyList(),
                eq("订单状态：支付失败"), eq(null)))
            .thenReturn(Map.of(
                "reply", "请重试支付 [1]",
                "source", "rag_ai",
                "retrieval", Map.of("decision", "multimodal_rag"),
                "citations", List.of()));

        R<Map<String, Object>> response = controller.chat(Map.of(
            "text", "这个怎么处理",
            "sessionId", "trial-123",
            "imageId", 12L));

        assertEquals(200, response.getCode());
        assertEquals("image", response.getData().get("inputModality"));
        assertEquals("unified_text_embedding", response.getData().get("retrievalMode"));
        assertEquals("trial-123", response.getData().get("sessionId"));
        verify(dialogService).sendWithMultimodalContext(
            eq("playground"), eq("trial-123"), eq("这个怎么处理"), eq("截图问答"),
            contains("订单状态：支付失败"), anyList(),
            eq("订单状态：支付失败"), eq(null));
    }

    @Test
    void missingSessionIdDoesNotUseSharedConversation() {
        when(dialogService.send(eq("playground"), anyString(), eq("测试问题"),
                eq("试聊"), eq(null), eq(null)))
            .thenReturn(Map.of("reply", "测试回答", "source", "faq"));

        R<Map<String, Object>> first = controller.chat(Map.of("text", "测试问题"));
        R<Map<String, Object>> second = controller.chat(Map.of("text", "测试问题"));

        String firstSession = String.valueOf(first.getData().get("sessionId"));
        String secondSession = String.valueOf(second.getData().get("sessionId"));
        assertTrue(firstSession.startsWith("admin-preview-"));
        assertTrue(secondSession.startsWith("admin-preview-"));
        assertTrue(!firstSession.equals(secondSession));
    }

    @Test
    void synthesisReturnsNonCacheableInlineAudio() {
        byte[] audio = new byte[] {1, 2, 3};
        when(synthesisService.synthesize("语音回答"))
            .thenReturn(new SpeechSynthesisService.SynthesisResult(
                audio, "audio/mpeg", "mp3", "tts-1", "openai", 25));

        ResponseEntity<byte[]> response = controller.synthesizeSpeech(
            Map.of("text", "语音回答"));

        assertEquals(MediaType.parseMediaType("audio/mpeg"),
            response.getHeaders().getContentType());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)
            .contains("reply.mp3"));
        assertArrayEquals(audio, response.getBody());
    }
}
