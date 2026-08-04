package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotChannelConfig;
import com.feisheng.bot.admin.mapper.BotChannelConfigMapper;
import com.feisheng.bot.gateway.client.DingTalkClient;
import com.feisheng.bot.gateway.dto.DingTalkMediaRequest;
import com.feisheng.bot.gateway.service.DingTalkMediaProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DingTalkInboundMediaProcessorTest {
    @Mock private DingTalkClient dingTalkClient;
    @Mock private BotChannelConfigMapper channelConfigMapper;
    @Mock private ImageOcrService imageOcrService;
    @Mock private SpeechTranscriptionService speechTranscriptionService;

    private DingTalkInboundMediaProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new DingTalkInboundMediaProcessor(
            dingTalkClient, channelConfigMapper, new ObjectMapper(), imageOcrService,
            speechTranscriptionService, "", "", "", 1024 * 1024,
            2 * 1024 * 1024, 8000, "ffmpeg", 10);
    }

    @Test
    void usesDingTalkVoiceRecognitionWithoutDownloadingAudio() {
        String result = processor.normalize(new DingTalkMediaRequest(
            "msg-1", "audio", "download-code", "  请帮我查询订单  ",
            null, "robot-code"));

        assertEquals("[客户发送了一条语音，以下为语音识别内容]\n请帮我查询订单", result);
        verifyNoInteractions(dingTalkClient, channelConfigMapper,
            imageOcrService, speechTranscriptionService);
    }

    @Test
    void downloadsImageRunsOcrAndDeletesTemporaryFile() throws Exception {
        when(channelConfigMapper.selectOne(any(Wrapper.class))).thenReturn(config());
        when(dingTalkClient.downloadRobotMessageFile(
            "app-key", "app-secret", "message-robot", "download-code", 1024 * 1024L))
            .thenReturn(new DingTalkClient.DownloadedMedia(
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47}, "image/png", "photo.png"));
        when(imageOcrService.supports("image.png")).thenReturn(true);
        when(imageOcrService.extract(any(Path.class), anyString())).thenReturn(
            new ImageOcrService.OcrResult("订单号 A100\n已支付", 800, 600,
                "tesseract", "chi_sim+eng", 120));

        String result = processor.normalize(new DingTalkMediaRequest(
            "msg-2", "picture", "download-code", null, null, "message-robot"));

        assertEquals("[客户发送了一张图片，以下为图片中的文字]\n订单号 A100\n已支付", result);
        ArgumentCaptor<Path> path = ArgumentCaptor.forClass(Path.class);
        verify(imageOcrService).extract(path.capture(), anyString());
        assertFalse(Files.exists(path.getValue()));
        verify(speechTranscriptionService, never()).transcribe(any(Path.class), anyString());
    }

    @Test
    void preservesImageCaptionAlongsideOcrText() throws Exception {
        when(channelConfigMapper.selectOne(any(Wrapper.class))).thenReturn(config());
        when(dingTalkClient.downloadRobotMessageFile(
            anyString(), anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(new DingTalkClient.DownloadedMedia(
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47}, "image/png", "photo.png"));
        when(imageOcrService.supports("image.png")).thenReturn(true);
        when(imageOcrService.extract(any(Path.class), anyString())).thenReturn(
            new ImageOcrService.OcrResult("手机号：13800138000", 800, 600,
                "tesseract", "chi_sim+eng", 120));

        String result = processor.normalize(new DingTalkMediaRequest(
            "msg-caption", "picture", "download-code", null, null, "message-robot",
            "这个怎么操作"));

        assertEquals("[客户附带问题]\n这个怎么操作\n\n"
            + "[客户发送了一张图片，以下为图片中的文字]\n手机号：13800138000", result);
    }

    @Test
    void downloadsMp3RunsAsrAndDeletesTemporaryFile() throws Exception {
        when(channelConfigMapper.selectOne(any(Wrapper.class))).thenReturn(config());
        when(dingTalkClient.downloadRobotMessageFile(
            anyString(), anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(new DingTalkClient.DownloadedMedia(
                "ID3audio".getBytes(StandardCharsets.US_ASCII), "audio/mpeg", "voice.mp3"));
        when(speechTranscriptionService.transcribe(any(Path.class), anyString())).thenReturn(
            new SpeechTranscriptionService.TranscriptionResult(
                "怎么申请发票", "glm-asr-2512", "zhipu", "zh", 8, 200));

        String result = processor.normalize(new DingTalkMediaRequest(
            "msg-3", "audio", "download-code", null, null, "message-robot"));

        assertEquals("[客户发送了一条语音，以下为语音转写内容]\n怎么申请发票", result);
        ArgumentCaptor<Path> path = ArgumentCaptor.forClass(Path.class);
        verify(speechTranscriptionService).transcribe(path.capture(),
            org.mockito.ArgumentMatchers.eq("dingtalk-audio.mp3"));
        assertFalse(Files.exists(path.getValue()));
    }

    @Test
    void returnsSafeMessageWhenDownloadFails() {
        when(channelConfigMapper.selectOne(any(Wrapper.class))).thenReturn(config());
        when(dingTalkClient.downloadRobotMessageFile(
            anyString(), anyString(), anyString(), anyString(), anyLong()))
            .thenThrow(new IllegalStateException("upstream detail"));

        DingTalkMediaProcessingException error = assertThrows(
            DingTalkMediaProcessingException.class,
            () -> processor.normalize(new DingTalkMediaRequest(
                "msg-4", "picture", "download-code", null, null, "message-robot")));

        assertEquals("媒体文件下载失败，请重新发送", error.getUserMessage());
        assertTrue(error.getCause() instanceof IllegalStateException);
    }

    private BotChannelConfig config() {
        BotChannelConfig config = new BotChannelConfig();
        config.setId(7L);
        config.setChannelType("dingtalk");
        config.setStatus(1);
        config.setConfigJson("{\"clientId\":\"app-key\","
            + "\"clientSecret\":\"app-secret\",\"robotCode\":\"config-robot\"}");
        return config;
    }
}
