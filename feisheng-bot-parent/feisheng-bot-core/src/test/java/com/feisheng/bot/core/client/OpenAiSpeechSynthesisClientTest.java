package com.feisheng.bot.core.client;

import com.feisheng.bot.core.service.SpeechSynthesisService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiSpeechSynthesisClientTest {
    @Test
    void keepsCompleteSpeechEndpointUnchanged() {
        assertEquals("https://api.openai.com/v1/audio/speech",
            OpenAiSpeechSynthesisClient.resolveSpeechUrl(
                "https://api.openai.com/v1/audio/speech"));
    }

    @Test
    void convertsOtherOpenAiEndpointsToSpeechEndpoint() {
        assertEquals("https://api.example.com/v1/audio/speech",
            OpenAiSpeechSynthesisClient.resolveSpeechUrl(
                "https://api.example.com/v1/audio/transcriptions"));
        assertEquals("https://api.example.com/v1/audio/speech",
            OpenAiSpeechSynthesisClient.resolveSpeechUrl(
                "https://api.example.com/v1/chat/completions"));
        assertEquals("https://api.example.com/v1/audio/speech",
            OpenAiSpeechSynthesisClient.resolveSpeechUrl(
                "https://api.example.com/v1/"));
    }

    @Test
    void sendsOpenAiCompatibleJsonRequestAndReturnsAudio() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        byte[] audio = "ID3-test-audio".getBytes(StandardCharsets.US_ASCII);
        server.expect(requestTo("https://api.example.com/v1/audio/speech"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
            .andExpect(header(HttpHeaders.ACCEPT, "audio/*"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().string(containsString("\"model\":\"tts-1\"")))
            .andExpect(content().string(containsString("\"input\":\"您好\"")))
            .andExpect(content().string(containsString("\"voice\":\"alloy\"")))
            .andExpect(content().string(containsString("\"response_format\":\"mp3\"")))
            .andExpect(content().string(containsString("\"speed\":1.0")))
            .andRespond(withSuccess(audio, MediaType.parseMediaType("audio/mpeg")));

        OpenAiSpeechSynthesisClient client = new OpenAiSpeechSynthesisClient(restTemplate);
        SpeechSynthesisClient.AudioResponse response = client.synthesize("您好", config(1024));

        assertArrayEquals(audio, response.audio());
        assertEquals("audio/mpeg", response.contentType());
        server.verify();
    }

    @Test
    void rejectsNonAudioSuccessResponse() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://api.example.com/v1/audio/speech"))
            .andRespond(withSuccess("{\"error\":\"not audio\"}", MediaType.APPLICATION_JSON));

        OpenAiSpeechSynthesisClient client = new OpenAiSpeechSynthesisClient(restTemplate);
        SpeechSynthesisService.SpeechSynthesisException error = assertThrows(
            SpeechSynthesisService.SpeechSynthesisException.class,
            () -> client.synthesize("您好", config(1024)));

        assertEquals(502, error.status());
        assertTrue(error.getMessage().contains("非音频"));
        server.verify();
    }

    @Test
    void rejectsResponseLargerThanConfiguredLimit() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://api.example.com/v1/audio/speech"))
            .andRespond(withSuccess(new byte[] {1, 2, 3, 4},
                MediaType.parseMediaType("audio/mpeg")));

        OpenAiSpeechSynthesisClient client = new OpenAiSpeechSynthesisClient(restTemplate);
        SpeechSynthesisService.SpeechSynthesisException error = assertThrows(
            SpeechSynthesisService.SpeechSynthesisException.class,
            () -> client.synthesize("您好", config(3)));

        assertEquals(502, error.status());
        assertTrue(error.getMessage().contains("大小限制"));
        server.verify();
    }

    @Test
    void redactsApiKeyFromUpstreamErrors() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://api.example.com/v1/audio/speech"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"Incorrect API key provided: test-key\"}"));

        OpenAiSpeechSynthesisClient client = new OpenAiSpeechSynthesisClient(restTemplate);
        SpeechSynthesisService.SpeechSynthesisException error = assertThrows(
            SpeechSynthesisService.SpeechSynthesisException.class,
            () -> client.synthesize("您好", config(1024)));

        assertEquals(502, error.status());
        assertTrue(error.getMessage().contains("[redacted]"));
        assertFalse(error.getMessage().contains("test-key"));
        server.verify();
    }

    private SpeechSynthesisService.SynthesisConfig config(long maxBytes) {
        return new SpeechSynthesisService.SynthesisConfig(
            "https://api.example.com/v1/audio/speech", "test-key", "tts-1",
            "openai", "alloy", "mp3", 1.0, maxBytes);
    }
}
