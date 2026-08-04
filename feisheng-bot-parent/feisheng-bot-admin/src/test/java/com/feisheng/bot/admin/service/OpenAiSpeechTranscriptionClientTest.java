package com.feisheng.bot.admin.service;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class OpenAiSpeechTranscriptionClientTest {
    @Test
    void keepsCompleteTranscriptionEndpointUnchanged() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            OpenAiSpeechTranscriptionClient.resolveTranscriptionUrl(
                "https://api.openai.com/v1/audio/transcriptions"));
    }

    @Test
    void convertsChatEndpointToTranscriptionEndpoint() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions",
            OpenAiSpeechTranscriptionClient.resolveTranscriptionUrl(
                "https://open.bigmodel.cn/api/paas/v4/chat/completions"));
    }

    @Test
    void appendsTranscriptionPathToOpenAiCompatibleBaseUrl() {
        assertEquals(
            "http://whisper:8000/v1/audio/transcriptions",
            OpenAiSpeechTranscriptionClient.resolveTranscriptionUrl(
                "http://whisper:8000/v1/"));
    }

    @Test
    void sendsOpenAiCompatibleMultipartRequest(@TempDir Path tempDir) throws Exception {
        Path audio = tempDir.resolve("question.wav");
        Files.write(audio, "RIFF0000WAVE acceptance audio".getBytes(StandardCharsets.US_ASCII));
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://api.example.com/v1/audio/transcriptions"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
            .andExpect(content().string(containsString("name=\"file\"")))
            .andExpect(content().string(containsString("filename=\"question.wav\"")))
            .andExpect(content().string(containsString("name=\"model\"")))
            .andExpect(content().string(containsString("whisper-1")))
            .andExpect(content().string(containsString("name=\"language\"")))
            .andExpect(content().string(containsString("zh")))
            .andRespond(withSuccess("{\"text\":\"如何重置密码？\"}", MediaType.APPLICATION_JSON));

        OpenAiSpeechTranscriptionClient client = new OpenAiSpeechTranscriptionClient(restTemplate);
        SpeechTranscriptionService.SpeechConfig config =
            new SpeechTranscriptionService.SpeechConfig(
                "https://api.example.com/v1/audio/transcriptions",
                "test-key", "whisper-1", "openai", "zh", "");

        String text = client.transcribe(audio, "question.wav", "audio/wav", config);

        assertEquals("如何重置密码？", text);
        server.verify();
    }

    @Test
    void redactsApiKeyFragmentsFromUpstreamErrors(@TempDir Path tempDir) throws Exception {
        Path audio = tempDir.resolve("question.wav");
        Files.write(audio, "RIFF0000WAVE acceptance audio".getBytes(StandardCharsets.US_ASCII));
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://api.example.com/v1/audio/transcriptions"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"Incorrect API key provided: abc123********xyz.\","
                    + "\"code\":\"invalid_api_key\"}}"));
        OpenAiSpeechTranscriptionClient client = new OpenAiSpeechTranscriptionClient(restTemplate);
        SpeechTranscriptionService.SpeechConfig config =
            new SpeechTranscriptionService.SpeechConfig(
                "https://api.example.com/v1/audio/transcriptions",
                "abc123-secret-value", "whisper-1", "openai", "zh", "");

        SpeechTranscriptionService.SpeechException error = assertThrows(
            SpeechTranscriptionService.SpeechException.class,
            () -> client.transcribe(audio, "question.wav", "audio/wav", config));

        assertEquals(502, error.status());
        assertTrue(error.getMessage().contains("invalid_api_key"));
        assertTrue(error.getMessage().contains("[redacted]"));
        assertFalse(error.getMessage().contains("abc123"));
        assertFalse(error.getMessage().contains("xyz"));
        server.verify();
    }
}
