package com.feisheng.bot.core.client;

import com.feisheng.bot.core.service.SpeechSynthesisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OpenAiSpeechSynthesisClient implements SpeechSynthesisClient {
    private final RestTemplate restTemplate;

    @Autowired
    public OpenAiSpeechSynthesisClient(
            RestTemplateBuilder builder,
            @Value("${speech.synthesis.connect-timeout-seconds:10}") long connectTimeoutSeconds,
            @Value("${speech.synthesis.timeout-seconds:120}") long timeoutSeconds) {
        this(builder
            .setConnectTimeout(Duration.ofSeconds(Math.max(1, connectTimeoutSeconds)))
            .setReadTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
            .build());
    }

    OpenAiSpeechSynthesisClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public AudioResponse synthesize(String text, SpeechSynthesisService.SynthesisConfig config) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("input", text);
        body.put("voice", config.voice());
        body.put("response_format", config.responseFormat());
        body.put("speed", config.speed());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.parseMediaType("audio/*")));
        if (StringUtils.hasText(config.apiKey())) {
            headers.setBearerAuth(config.apiKey());
        }

        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                config.apiUrl(), HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
            byte[] audio = response.getBody();
            String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            validateResponse(audio, contentType, response.getHeaders().getContentLength(), config.maxBytes());
            return new AudioResponse(audio, contentType);
        } catch (HttpStatusCodeException e) {
            throw new SpeechSynthesisService.SpeechSynthesisException(502,
                "语音合成服务返回 " + e.getStatusCode().value() + ": "
                    + sanitizeError(e.getResponseBodyAsString(), config.apiKey(), 500), e);
        } catch (SpeechSynthesisService.SpeechSynthesisException e) {
            throw e;
        } catch (Exception e) {
            throw new SpeechSynthesisService.SpeechSynthesisException(502,
                "语音合成服务调用失败: "
                    + sanitizeError(e.getMessage(), config.apiKey(), 500), e);
        }
    }

    private void validateResponse(byte[] audio, String contentType,
                                  long contentLength, long maxBytes) {
        if (contentLength > maxBytes) {
            throw new SpeechSynthesisService.SpeechSynthesisException(502,
                "语音合成响应超过大小限制");
        }
        if (audio == null || audio.length == 0) {
            throw new SpeechSynthesisService.SpeechSynthesisException(502,
                "语音合成服务返回空音频");
        }
        if (audio.length > maxBytes) {
            throw new SpeechSynthesisService.SpeechSynthesisException(502,
                "语音合成响应超过大小限制");
        }
        if (!isAudioContentType(contentType)) {
            throw new SpeechSynthesisService.SpeechSynthesisException(502,
                "语音合成服务返回了非音频内容");
        }
    }

    private boolean isAudioContentType(String value) {
        if (!StringUtils.hasText(value)) return false;
        try {
            return "audio".equalsIgnoreCase(MediaType.parseMediaType(value).getType());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String resolveSpeechUrl(String configuredUrl) {
        String value = configuredUrl == null ? "" : configuredUrl.trim();
        if (value.isEmpty()) return "";
        value = value.replaceAll("/+$", "");
        if (value.endsWith("/audio/speech")) return value;
        for (String suffix : new String[] {
                "/chat/completions", "/audio/transcriptions", "/embeddings"}) {
            if (value.endsWith(suffix)) {
                return value.substring(0, value.length() - suffix.length()) + "/audio/speech";
            }
        }
        return value + "/audio/speech";
    }

    static String sanitizeError(String value, String apiKey, int maxLength) {
        if (value == null || value.isBlank()) return "无错误详情";
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (StringUtils.hasText(apiKey)) {
            normalized = normalized.replace(apiKey, "[redacted]");
        }
        normalized = normalized
            .replaceAll("(?i)(api[ _-]?key(?:\\s+provided)?\\s*[:=]\\s*)[^\\s\"',}]+",
                "$1[redacted]")
            .replaceAll("(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?)[^\\s\"',}]+",
                "$1[redacted]");
        return normalized.length() <= maxLength
            ? normalized : normalized.substring(0, maxLength) + "...";
    }
}
