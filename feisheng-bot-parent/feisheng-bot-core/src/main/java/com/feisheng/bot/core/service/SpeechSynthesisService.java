package com.feisheng.bot.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.common.exception.BusinessException;
import com.feisheng.bot.core.client.AdminClient;
import com.feisheng.bot.core.client.OpenAiSpeechSynthesisClient;
import com.feisheng.bot.core.client.SpeechSynthesisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SpeechSynthesisService {
    private static final Logger log = LoggerFactory.getLogger(SpeechSynthesisService.class);
    private static final Set<String> SUPPORTED_FORMATS = Set.of(
        "mp3", "opus", "aac", "flac", "wav");

    private final AdminClient adminClient;
    private final SpeechSynthesisClient client;
    private final ObjectMapper objectMapper;
    private final boolean fallbackEnabled;
    private final String fallbackApiUrl;
    private final String fallbackApiKey;
    private final String fallbackModel;
    private final String fallbackVoice;
    private final String fallbackResponseFormat;
    private final double fallbackSpeed;
    private final int maxInputChars;
    private final long maxBytes;

    public SpeechSynthesisService(
            AdminClient adminClient,
            SpeechSynthesisClient client,
            ObjectMapper objectMapper,
            @Value("${speech.synthesis.enabled:false}") boolean fallbackEnabled,
            @Value("${speech.synthesis.api-url:https://api.openai.com/v1/audio/speech}") String fallbackApiUrl,
            @Value("${speech.synthesis.api-key:}") String fallbackApiKey,
            @Value("${speech.synthesis.model:tts-1}") String fallbackModel,
            @Value("${speech.synthesis.voice:alloy}") String fallbackVoice,
            @Value("${speech.synthesis.response-format:mp3}") String fallbackResponseFormat,
            @Value("${speech.synthesis.speed:1.0}") double fallbackSpeed,
            @Value("${speech.synthesis.max-input-chars:4000}") int maxInputChars,
            @Value("${speech.synthesis.max-bytes:10485760}") long maxBytes) {
        this.adminClient = adminClient;
        this.client = client;
        this.objectMapper = objectMapper;
        this.fallbackEnabled = fallbackEnabled;
        this.fallbackApiUrl = fallbackApiUrl;
        this.fallbackApiKey = fallbackApiKey;
        this.fallbackModel = fallbackModel;
        this.fallbackVoice = fallbackVoice;
        this.fallbackResponseFormat = fallbackResponseFormat;
        this.fallbackSpeed = fallbackSpeed;
        this.maxInputChars = Math.max(1, maxInputChars);
        this.maxBytes = Math.max(1, maxBytes);
    }

    public SynthesisResult synthesize(String text) {
        String input = spokenText(text);
        if (!StringUtils.hasText(input)) {
            throw new SpeechSynthesisException(400, "语音合成文本不能为空");
        }
        if (input.length() > maxInputChars) {
            throw new SpeechSynthesisException(400,
                "语音合成文本不能超过 " + maxInputChars + " 个字符");
        }

        SynthesisConfig config = resolveConfig();
        long started = System.currentTimeMillis();
        SpeechSynthesisClient.AudioResponse response = client.synthesize(input, config);
        validateResponse(response);
        return new SynthesisResult(response.audio(), response.contentType(), config.responseFormat(),
            config.model(), config.provider(), System.currentTimeMillis() - started);
    }

    private String spokenText(String value) {
        if (value == null) return "";
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        int footer = normalized.indexOf("\n参考来源：");
        if (footer >= 0) normalized = normalized.substring(0, footer);
        return normalized.replaceAll("\\s*\\[\\d+]", "").trim();
    }

    public SynthesisStatus status() {
        try {
            SynthesisConfig config = resolveConfig();
            return new SynthesisStatus(true, config.provider(), config.model(), config.voice(),
                config.responseFormat(), config.speed(), maxInputChars, maxBytes, null);
        } catch (SpeechSynthesisException e) {
            return new SynthesisStatus(false, null, null, null, null, null,
                maxInputChars, maxBytes, e.getMessage());
        }
    }

    private void validateResponse(SpeechSynthesisClient.AudioResponse response) {
        if (response == null || response.audio() == null || response.audio().length == 0) {
            throw new SpeechSynthesisException(502, "语音合成服务返回空音频");
        }
        if (response.audio().length > maxBytes) {
            throw new SpeechSynthesisException(502, "语音合成响应超过大小限制");
        }
        if (!isAudioContentType(response.contentType())) {
            throw new SpeechSynthesisException(502, "语音合成服务返回了非音频内容");
        }
    }

    private SynthesisConfig resolveConfig() {
        for (Map<String, Object> model : activeModels()) {
            if (!"TTS".equalsIgnoreCase(stringValue(model.get("modelType")))) continue;
            Object status = model.get("status");
            if (status instanceof Number number && number.intValue() != 1) continue;

            Map<String, Object> parameters = parameters(model.get("parameters"));
            return config(
                stringValue(model.get("apiUrl")),
                stringValue(model.get("apiKey")),
                stringValue(model.get("modelName")),
                stringValue(model.get("provider")),
                firstNonBlank(stringValue(parameters.get("voice")), fallbackVoice),
                firstNonBlank(
                    stringValue(parameters.get("response_format")),
                    stringValue(parameters.get("responseFormat")),
                    stringValue(parameters.get("format")),
                    fallbackResponseFormat),
                doubleValue(parameters.get("speed"), fallbackSpeed));
        }
        if (!fallbackEnabled) {
            throw new SpeechSynthesisException(503,
                "语音合成尚未配置，请启用 TTS 类型模型或设置 speech.synthesis.enabled=true");
        }
        return config(fallbackApiUrl, fallbackApiKey, fallbackModel,
            "openai-compatible", fallbackVoice, fallbackResponseFormat, fallbackSpeed);
    }

    private List<Map<String, Object>> activeModels() {
        List<Map<String, Object>> models = adminClient.getActiveModels();
        return models == null ? Collections.emptyList() : models;
    }

    private SynthesisConfig config(String apiUrl, String apiKey, String model,
                                   String provider, String voice, String responseFormat,
                                   double speed) {
        String resolvedUrl = OpenAiSpeechSynthesisClient.resolveSpeechUrl(apiUrl);
        if (!StringUtils.hasText(resolvedUrl) || !isHttpUrl(resolvedUrl)) {
            throw new SpeechSynthesisException(503, "语音合成 API 地址未配置或无效");
        }
        String resolvedApiKey = apiKey == null ? "" : apiKey.trim();
        if (StringUtils.hasText(resolvedApiKey)
                && !resolvedApiKey.matches("[A-Za-z0-9\\-._~+/]+=*")) {
            throw new SpeechSynthesisException(503,
                "语音合成 API 密钥包含 Authorization 不支持的字符");
        }
        String resolvedModel = firstNonBlank(model, "tts-1");
        String resolvedProvider = firstNonBlank(provider, "openai-compatible");
        String resolvedVoice = firstNonBlank(voice, "alloy");
        String resolvedFormat = firstNonBlank(responseFormat, "mp3").toLowerCase(Locale.ROOT);
        if (!SUPPORTED_FORMATS.contains(resolvedFormat)) {
            throw new SpeechSynthesisException(503,
                "不支持的语音合成响应格式: " + resolvedFormat);
        }
        if (!Double.isFinite(speed) || speed < 0.25 || speed > 4.0) {
            throw new SpeechSynthesisException(503,
                "语音合成语速必须在 0.25 到 4.0 之间");
        }
        return new SynthesisConfig(resolvedUrl, resolvedApiKey, resolvedModel,
            resolvedProvider, resolvedVoice, resolvedFormat, speed, maxBytes);
    }

    private Map<String, Object> parameters(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        if (!StringUtils.hasText(stringValue(value))) return Collections.emptyMap();
        try {
            return objectMapper.readValue(stringValue(value),
                new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Ignoring invalid TTS model parameters: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                && StringUtils.hasText(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
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

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return "";
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return StringUtils.hasText(stringValue(value))
                ? Double.parseDouble(stringValue(value)) : fallback;
        } catch (NumberFormatException e) {
            throw new SpeechSynthesisException(503, "语音合成语速配置无效");
        }
    }

    public record SynthesisConfig(String apiUrl, String apiKey, String model,
                                  String provider, String voice,
                                  String responseFormat, double speed, long maxBytes) {}

    public record SynthesisResult(byte[] audio, String contentType, String responseFormat,
                                  String model, String provider, long durationMs) {}

    public record SynthesisStatus(boolean available, String provider, String model,
                                  String voice, String responseFormat, Double speed,
                                  int maxInputChars, long maxBytes, String error) {}

    public static class SpeechSynthesisException extends BusinessException {
        public SpeechSynthesisException(int status, String message) {
            super(status, message);
        }

        public SpeechSynthesisException(int status, String message, Throwable cause) {
            super(status, message);
            initCause(cause);
        }

        public int status() {
            return getCode();
        }
    }
}
