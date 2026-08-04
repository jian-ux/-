package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotAiModelConfig;
import com.feisheng.bot.admin.mapper.BotAiModelConfigMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpeechTranscriptionServiceTest {
    @Test
    void transcribesValidAudioWithConfiguredSpeechModelAndDeletesTempFile() {
        BotAiModelConfigMapper mapper = mock(BotAiModelConfigMapper.class);
        BotAiModelConfig model = new BotAiModelConfig();
        model.setModelName("whisper-large-v3");
        model.setProvider("local-whisper");
        model.setApiUrl("http://whisper:8000/v1");
        model.setApiKey("");
        when(mapper.selectOne(any())).thenReturn(model);

        AtomicReference<Path> receivedPath = new AtomicReference<>();
        SpeechTranscriptionClient client = (path, fileName, contentType, config) -> {
            receivedPath.set(path);
            assertTrue(Files.exists(path));
            assertEquals("question.wav", fileName);
            assertEquals("audio/wav", contentType);
            assertEquals("http://whisper:8000/v1/audio/transcriptions", config.apiUrl());
            return "  如何重置密码？  ";
        };
        SpeechTranscriptionService service = service(mapper, client, false, "", "");
        MockMultipartFile audio = new MockMultipartFile(
            "file", "question.wav", "audio/wav", wavHeader());

        SpeechTranscriptionService.TranscriptionResult result = service.transcribe(audio);

        assertEquals("如何重置密码？", result.text());
        assertEquals("whisper-large-v3", result.model());
        assertEquals("local-whisper", result.provider());
        assertFalse(Files.exists(receivedPath.get()));
    }

    @Test
    void transcribesExistingTemporaryPathWithoutDeletingCallerOwnedFile() throws Exception {
        BotAiModelConfigMapper mapper = mock(BotAiModelConfigMapper.class);
        when(mapper.selectOne(any())).thenReturn(speechModel());
        SpeechTranscriptionClient client = (path, fileName, contentType, config) -> {
            assertEquals("dingtalk-audio.wav", fileName);
            assertEquals("audio/wav", contentType);
            return "渠道语音内容";
        };
        SpeechTranscriptionService service = service(mapper, client, false, "", "");
        Path audio = Files.createTempFile("speech-path-test-", ".wav");
        try {
            Files.write(audio, wavHeader());

            SpeechTranscriptionService.TranscriptionResult result =
                service.transcribe(audio, "dingtalk-audio.wav");

            assertEquals("渠道语音内容", result.text());
            assertTrue(Files.exists(audio));
        } finally {
            Files.deleteIfExists(audio);
        }
    }

    @Test
    void rejectsFileWhoseContentDoesNotMatchAudioExtension() {
        BotAiModelConfigMapper mapper = mock(BotAiModelConfigMapper.class);
        when(mapper.selectOne(any())).thenReturn(speechModel());
        SpeechTranscriptionClient client = mock(SpeechTranscriptionClient.class);
        SpeechTranscriptionService service = service(mapper, client, false, "", "");
        MockMultipartFile fake = new MockMultipartFile(
            "file", "fake.mp3", "audio/mpeg", "not audio".getBytes(StandardCharsets.UTF_8));

        SpeechTranscriptionService.SpeechException error = assertThrows(
            SpeechTranscriptionService.SpeechException.class,
            () -> service.transcribe(fake));

        assertEquals(400, error.status());
        assertTrue(error.getMessage().contains("格式与扩展名不一致"));
    }

    @Test
    void reportsUnavailableWhenNoDatabaseModelOrFallbackIsEnabled() {
        BotAiModelConfigMapper mapper = mock(BotAiModelConfigMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        SpeechTranscriptionService service = service(
            mapper, mock(SpeechTranscriptionClient.class), false,
            "https://api.openai.com/v1/audio/transcriptions", "");

        SpeechTranscriptionService.SpeechStatus status = service.status();

        assertFalse(status.available());
        assertTrue(status.error().contains("SPEECH_ENABLED=true"));
    }

    @Test
    void reportsUnavailableWhenFallbackApiKeyCannotBeUsedInAuthorizationHeader() {
        BotAiModelConfigMapper mapper = mock(BotAiModelConfigMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        SpeechTranscriptionService service = service(
            mapper, mock(SpeechTranscriptionClient.class), true,
            "https://api.openai.com/v1/audio/transcriptions", "你的密钥");

        SpeechTranscriptionService.SpeechStatus status = service.status();

        assertFalse(status.available());
        assertTrue(status.error().contains("API 密钥"));
        assertTrue(status.error().contains("占位符"));
    }

    @Test
    void rejectsWebmBeforeCallingGlmAsr() {
        BotAiModelConfigMapper mapper = mock(BotAiModelConfigMapper.class);
        BotAiModelConfig model = speechModel();
        model.setModelName("glm-asr-2512");
        model.setProvider("zhipu");
        model.setApiUrl("https://open.bigmodel.cn/api/paas/v4/audio/transcriptions");
        when(mapper.selectOne(any())).thenReturn(model);
        SpeechTranscriptionClient client = mock(SpeechTranscriptionClient.class);
        SpeechTranscriptionService service = service(mapper, client, false, "", "");
        MockMultipartFile audio = new MockMultipartFile(
            "file", "recording.webm", "audio/webm",
            new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0});

        SpeechTranscriptionService.SpeechException error = assertThrows(
            SpeechTranscriptionService.SpeechException.class,
            () -> service.transcribe(audio));

        assertEquals(400, error.status());
        assertTrue(error.getMessage().contains("wav、mp3"));
        verifyNoInteractions(client);
    }

    @Test
    void reportsGlmAsrFormatsInStatus() {
        BotAiModelConfigMapper mapper = mock(BotAiModelConfigMapper.class);
        BotAiModelConfig model = speechModel();
        model.setModelName("glm-asr-2512");
        when(mapper.selectOne(any())).thenReturn(model);
        SpeechTranscriptionService service = service(
            mapper, mock(SpeechTranscriptionClient.class), false, "", "");

        SpeechTranscriptionService.SpeechStatus status = service.status();

        assertTrue(status.available());
        assertEquals(java.util.Set.of("wav", "mp3"), status.formats());
    }

    private SpeechTranscriptionService service(BotAiModelConfigMapper mapper,
                                               SpeechTranscriptionClient client,
                                               boolean fallbackEnabled,
                                               String fallbackUrl,
                                               String fallbackKey) {
        return new SpeechTranscriptionService(mapper, client, fallbackEnabled,
            fallbackUrl, fallbackKey, "whisper-1", "zh", "", 25 * 1024 * 1024L);
    }

    private BotAiModelConfig speechModel() {
        BotAiModelConfig model = new BotAiModelConfig();
        model.setModelName("whisper-1");
        model.setProvider("openai");
        model.setApiUrl("https://api.openai.com/v1/audio/transcriptions");
        model.setApiKey("test-key");
        return model;
    }

    private byte[] wavHeader() {
        byte[] bytes = new byte[44];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        return bytes;
    }
}
