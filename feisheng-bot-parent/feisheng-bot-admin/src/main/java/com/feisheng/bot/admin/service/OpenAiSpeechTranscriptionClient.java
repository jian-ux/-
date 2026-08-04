package com.feisheng.bot.admin.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

@Component
public class OpenAiSpeechTranscriptionClient implements SpeechTranscriptionClient {
    private final RestTemplate restTemplate;

    @Autowired
    public OpenAiSpeechTranscriptionClient(
            RestTemplateBuilder builder,
            @Value("${speech.transcription.connect-timeout-seconds:10}") long connectTimeoutSeconds,
            @Value("${speech.transcription.timeout-seconds:120}") long timeoutSeconds) {
        this(builder
            .setConnectTimeout(Duration.ofSeconds(Math.max(1, connectTimeoutSeconds)))
            .setReadTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
            .build());
    }

    OpenAiSpeechTranscriptionClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String transcribe(Path audioPath, String fileName, String contentType,
                             SpeechTranscriptionService.SpeechConfig config) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        Resource resource = new FileSystemResource(audioPath) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(contentType));
        fileHeaders.setContentDisposition(ContentDisposition.formData()
            .name("file")
            .filename(fileName)
            .build());
        body.add("file", new HttpEntity<>(resource, fileHeaders));
        body.add("model", config.model());
        body.add("response_format", "json");
        if (StringUtils.hasText(config.language())) body.add("language", config.language());
        if (StringUtils.hasText(config.prompt())) body.add("prompt", config.prompt());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (StringUtils.hasText(config.apiKey())) headers.setBearerAuth(config.apiKey());

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                config.apiUrl(), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            Map<String, Object> responseBody = response.getBody();
            Object text = responseBody == null ? null : responseBody.get("text");
            if (text == null && responseBody != null && responseBody.get("data") instanceof Map<?, ?> data) {
                text = data.get("text");
            }
            return text == null ? "" : text.toString();
        } catch (HttpStatusCodeException e) {
            throw new SpeechTranscriptionService.SpeechException(502,
                "语音转写服务返回 " + e.getStatusCode().value() + ": "
                    + sanitizeError(e.getResponseBodyAsString(), config.apiKey(), 500), e);
        } catch (SpeechTranscriptionService.SpeechException e) {
            throw e;
        } catch (Exception e) {
            throw new SpeechTranscriptionService.SpeechException(502,
                "语音转写服务调用失败: " + e.getMessage(), e);
        }
    }

    static String resolveTranscriptionUrl(String configuredUrl) {
        String value = configuredUrl == null ? "" : configuredUrl.trim();
        if (value.isEmpty()) return "";
        value = value.replaceAll("/+$", "");
        if (value.endsWith("/audio/transcriptions")) return value;
        if (value.endsWith("/chat/completions")) {
            return value.substring(0, value.length() - "/chat/completions".length())
                + "/audio/transcriptions";
        }
        if (value.endsWith("/embeddings")) {
            return value.substring(0, value.length() - "/embeddings".length())
                + "/audio/transcriptions";
        }
        return value + "/audio/transcriptions";
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
