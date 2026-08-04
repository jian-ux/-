package com.feisheng.bot.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.core.client.AdminClient;
import com.feisheng.bot.core.client.SpeechSynthesisClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpeechSynthesisServiceTest {
    @Test
    void usesFirstActiveDatabaseTtsModelAndParameterOverrides() {
        AdminClient adminClient = mock(AdminClient.class);
        SpeechSynthesisClient client = mock(SpeechSynthesisClient.class);
        when(adminClient.getActiveModels()).thenReturn(List.of(
            model("LLM", "https://api.example.com/v1/chat/completions", "chat", null),
            model("TTS", "https://voice.example.com/v1", "voice-pro",
                "{\"voice\":\"nova\",\"response_format\":\"wav\",\"speed\":1.25}")));
        byte[] audio = "RIFF0000WAVE".getBytes(StandardCharsets.US_ASCII);
        when(client.synthesize(eq("需要语音回答"), any()))
            .thenReturn(new SpeechSynthesisClient.AudioResponse(audio, "audio/wav"));
        SpeechSynthesisService service = service(
            adminClient, client, false, "", "", "", "alloy", "mp3", 1.0, 100, 1024);

        SpeechSynthesisService.SynthesisResult result = service.synthesize("  需要语音回答  ");

        ArgumentCaptor<SpeechSynthesisService.SynthesisConfig> configCaptor =
            ArgumentCaptor.forClass(SpeechSynthesisService.SynthesisConfig.class);
        verify(client).synthesize(eq("需要语音回答"), configCaptor.capture());
        SpeechSynthesisService.SynthesisConfig config = configCaptor.getValue();
        assertEquals("https://voice.example.com/v1/audio/speech", config.apiUrl());
        assertEquals("voice-pro", config.model());
        assertEquals("zhipu", config.provider());
        assertEquals("nova", config.voice());
        assertEquals("wav", config.responseFormat());
        assertEquals(1.25, config.speed());
        assertArrayEquals(audio, result.audio());
        assertEquals("audio/wav", result.contentType());
    }

    @Test
    void usesApplicationFallbackWhenDatabaseHasNoTtsModel() {
        AdminClient adminClient = mock(AdminClient.class);
        SpeechSynthesisClient client = mock(SpeechSynthesisClient.class);
        when(adminClient.getActiveModels()).thenReturn(List.of(
            model("LLM", "https://api.example.com/v1", "chat", null)));
        when(client.synthesize(eq("fallback"), any()))
            .thenReturn(new SpeechSynthesisClient.AudioResponse(
                new byte[] {1, 2, 3}, "audio/mpeg"));
        SpeechSynthesisService service = service(
            adminClient, client, true, "https://api.openai.com/v1", "fallback-key",
            "tts-1-hd", "echo", "mp3", 0.9, 100, 1024);

        SpeechSynthesisService.SynthesisResult result = service.synthesize("fallback");

        ArgumentCaptor<SpeechSynthesisService.SynthesisConfig> configCaptor =
            ArgumentCaptor.forClass(SpeechSynthesisService.SynthesisConfig.class);
        verify(client).synthesize(eq("fallback"), configCaptor.capture());
        SpeechSynthesisService.SynthesisConfig config = configCaptor.getValue();
        assertEquals("https://api.openai.com/v1/audio/speech", config.apiUrl());
        assertEquals("fallback-key", config.apiKey());
        assertEquals("tts-1-hd", config.model());
        assertEquals("openai-compatible", config.provider());
        assertEquals("echo", config.voice());
        assertEquals(0.9, config.speed());
        assertEquals("mp3", result.responseFormat());
    }

    @Test
    void reportsUnavailableWithoutDatabaseModelOrEnabledFallback() {
        AdminClient adminClient = mock(AdminClient.class);
        when(adminClient.getActiveModels()).thenReturn(List.of());
        SpeechSynthesisService service = service(
            adminClient, mock(SpeechSynthesisClient.class), false,
            "https://api.openai.com/v1/audio/speech", "", "tts-1",
            "alloy", "mp3", 1.0, 100, 1024);

        SpeechSynthesisService.SynthesisStatus status = service.status();

        assertFalse(status.available());
        assertTrue(status.error().contains("TTS"));
    }

    @Test
    void rejectsBlankAndOversizedInputBeforeResolvingConfiguration() {
        AdminClient adminClient = mock(AdminClient.class);
        SpeechSynthesisClient client = mock(SpeechSynthesisClient.class);
        SpeechSynthesisService service = service(
            adminClient, client, false, "", "", "", "alloy", "mp3", 1.0, 5, 1024);

        SpeechSynthesisService.SpeechSynthesisException blank = assertThrows(
            SpeechSynthesisService.SpeechSynthesisException.class,
            () -> service.synthesize("   "));
        SpeechSynthesisService.SpeechSynthesisException oversized = assertThrows(
            SpeechSynthesisService.SpeechSynthesisException.class,
            () -> service.synthesize("123456"));

        assertEquals(400, blank.status());
        assertEquals(400, oversized.status());
        verifyNoInteractions(adminClient, client);
    }

    @Test
    void rejectsOversizedOrNonAudioClientResponses() {
        AdminClient adminClient = mock(AdminClient.class);
        when(adminClient.getActiveModels()).thenReturn(List.of(
            model("TTS", "https://api.example.com/v1", "tts-1", null)));
        SpeechSynthesisClient client = mock(SpeechSynthesisClient.class);
        SpeechSynthesisService service = service(
            adminClient, client, false, "", "", "", "alloy", "mp3", 1.0, 100, 3);

        when(client.synthesize(eq("large"), any()))
            .thenReturn(new SpeechSynthesisClient.AudioResponse(
                new byte[] {1, 2, 3, 4}, "audio/mpeg"));
        SpeechSynthesisService.SpeechSynthesisException oversized = assertThrows(
            SpeechSynthesisService.SpeechSynthesisException.class,
            () -> service.synthesize("large"));

        when(client.synthesize(eq("json"), any()))
            .thenReturn(new SpeechSynthesisClient.AudioResponse(
                new byte[] {1}, "application/json"));
        SpeechSynthesisService.SpeechSynthesisException nonAudio = assertThrows(
            SpeechSynthesisService.SpeechSynthesisException.class,
            () -> service.synthesize("json"));

        assertEquals(502, oversized.status());
        assertTrue(oversized.getMessage().contains("大小限制"));
        assertEquals(502, nonAudio.status());
        assertTrue(nonAudio.getMessage().contains("非音频"));
    }

    @Test
    void removesCitationMarkersAndFooterBeforeSynthesis() {
        AdminClient adminClient = mock(AdminClient.class);
        when(adminClient.getActiveModels()).thenReturn(List.of(
            model("TTS", "https://api.example.com/v1", "tts-1", null)));
        SpeechSynthesisClient client = mock(SpeechSynthesisClient.class);
        when(client.synthesize(eq("请重新支付"), any()))
            .thenReturn(new SpeechSynthesisClient.AudioResponse(
                new byte[] {1}, "audio/mpeg"));
        SpeechSynthesisService service = service(
            adminClient, client, false, "", "", "", "alloy", "mp3", 1.0, 100, 1024);

        service.synthesize("请重新支付 [1]\n\n参考来源：\n[1] 支付故障手册");

        verify(client).synthesize(eq("请重新支付"), any());
    }

    @Test
    void rejectsInvalidSpeedAndPcmFormat() {
        AdminClient adminClient = mock(AdminClient.class);
        when(adminClient.getActiveModels()).thenReturn(List.of());

        SpeechSynthesisService invalidSpeed = service(
            adminClient, mock(SpeechSynthesisClient.class), true,
            "https://api.example.com/v1", "", "tts-1", "alloy", "mp3",
            4.1, 100, 1024);
        SpeechSynthesisService invalidPcm = service(
            adminClient, mock(SpeechSynthesisClient.class), true,
            "https://api.example.com/v1", "", "tts-1", "alloy", "pcm",
            1.0, 100, 1024);

        assertFalse(invalidSpeed.status().available());
        assertTrue(invalidSpeed.status().error().contains("0.25"));
        assertFalse(invalidPcm.status().available());
        assertTrue(invalidPcm.status().error().contains("pcm"));
    }

    private SpeechSynthesisService service(
            AdminClient adminClient, SpeechSynthesisClient client,
            boolean fallbackEnabled, String fallbackUrl, String fallbackKey,
            String fallbackModel, String voice, String format,
            double speed, int maxInputChars, long maxBytes) {
        return new SpeechSynthesisService(adminClient, client, new ObjectMapper(),
            fallbackEnabled, fallbackUrl, fallbackKey, fallbackModel,
            voice, format, speed, maxInputChars, maxBytes);
    }

    private Map<String, Object> model(String type, String apiUrl,
                                      String modelName, String parameters) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("modelType", type);
        model.put("apiUrl", apiUrl);
        model.put("apiKey", "db-key");
        model.put("modelName", modelName);
        model.put("provider", "zhipu");
        model.put("parameters", parameters);
        model.put("status", 1);
        return model;
    }
}
