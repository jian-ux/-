package com.feisheng.bot.core.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.core.dto.ChatResponse;
import com.feisheng.bot.core.dto.LlmFailureType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * LLM HTTP 调用客户端：统一处理 OpenAI 兼容 API 的调用、重试、响应解析
 */
@Component
public class LlmHttpClient {
    private static final Logger log = LoggerFactory.getLogger(LlmHttpClient.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private RestTemplate rest;
    private HttpClient policyHttpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ai.llm.connect-timeout:10000}")
    private int connectTimeout;

    @Value("${ai.llm.read-timeout:60000}")
    private int readTimeout;

    @Value("${ai.llm.max-retries:2}")
    private int maxRetries;

    @Value("${ai.llm.temperature:0.0}")
    private double temperature;

    @Value("${ai.llm.max-output-tokens:1024}")
    private int maxOutputTokens;

    @Value("${ai.llm.deepseek-thinking-enabled:false}")
    private boolean deepSeekThinkingEnabled;

    @Value("${ai.llm.local.keep-alive:-1}")
    private long localKeepAlive;

    @Value("${ai.llm.system-prompt:You are a helpful customer service assistant.}")
    private String defaultSystemPrompt;

    @PostConstruct
    public void init() {
        this.rest = restTemplate(readTimeout);
        this.policyHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeout)))
                .build();
    }

    private RestTemplate restTemplate(int requestReadTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(Math.max(1, requestReadTimeout));
        return new RestTemplate(factory);
    }

    /**
     * 调用 LLM API，带重试和超时
     *
     * @param apiUrl   API 地址
     * @param apiKey   API Key
     * @param model    模型名称
     * @param systemPrompt 系统提示词（传 null 则使用默认值）
     * @param userPrompt 用户输入
     * @param providerCode 供应商代码（用于返回）
     * @return ChatResponse
     */
    public ChatResponse call(String apiUrl, String apiKey, String model,
                              String systemPrompt, String userPrompt,
                              String providerCode) {
        return call(rest, apiUrl, apiKey, model, systemPrompt, userPrompt,
            providerCode, maxRetries, null);
    }

    /** Requests provider-enforced structured JSON for callers that validate its shape. */
    public ChatResponse callJsonSchema(String apiUrl, String apiKey, String model,
                                       String systemPrompt, String userPrompt,
                                       String providerCode, Map<String, Object> schema) {
        return call(rest, apiUrl, apiKey, model, systemPrompt, userPrompt,
            providerCode, maxRetries, Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                    "name", "structured_response",
                    "strict", true,
                    "schema", schema)));
    }

    /** Uses an isolated policy for offline jobs without slowing interactive chat calls. */
    public ChatResponse callWithPolicy(String apiUrl, String apiKey, String model,
                                       String systemPrompt, String userPrompt,
                                       String providerCode, int requestReadTimeout,
                                       int requestMaxRetries) {
        return callWithDeadline(apiUrl, apiKey, model, systemPrompt, userPrompt, providerCode,
                Math.max(1, requestReadTimeout), Math.max(0, requestMaxRetries), null);
    }

    /** Uses request-local timeout/retry limits while requiring provider JSON Schema support. */
    public ChatResponse callJsonSchemaWithPolicy(String apiUrl, String apiKey, String model,
                                                 String systemPrompt, String userPrompt,
                                                 String providerCode, Map<String, Object> schema,
                                                 int requestReadTimeout, int requestMaxRetries) {
        return callWithDeadline(apiUrl, apiKey, model, systemPrompt, userPrompt, providerCode,
                Math.max(1, requestReadTimeout), Math.max(0, requestMaxRetries), Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", "structured_response",
                        "strict", true,
                        "schema", schema)));
    }

    private ChatResponse callWithDeadline(String apiUrl, String apiKey, String model,
                                          String systemPrompt, String userPrompt,
                                          String providerCode, int timeoutMs, int retries,
                                          Map<String, Object> responseFormat) {
        String resolvedModel = model != null ? model : "gpt-4o-mini";
        String resolvedSystemPrompt = systemPrompt != null ? systemPrompt : defaultSystemPrompt;
        Exception lastException = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            long attemptDeadlineNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            try {
                Map<String, Object> body = requestBody(resolvedModel, resolvedSystemPrompt,
                        userPrompt, apiUrl, providerCode, responseFormat);
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(apiUrl))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
                if (apiKey != null && !apiKey.isEmpty()) {
                    requestBuilder.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
                }
                HttpClient client = policyHttpClient == null
                        ? HttpClient.newHttpClient() : policyHttpClient;
                CompletableFuture<HttpResponse<String>> responseFuture = client.sendAsync(
                        requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                HttpResponse<String> response;
                try {
                    long remainingNanos = attemptDeadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        responseFuture.cancel(true);
                        throw new TimeoutException("context model request deadline exceeded");
                    }
                    response = responseFuture.get(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (TimeoutException timeout) {
                    responseFuture.cancel(true);
                    throw timeout;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return failureResponse("AI service unavailable.",
                            classifyHttpFailure(response.statusCode(), response.body()));
                }
                Map<String, Object> responseBody;
                try {
                    responseBody = objectMapper.readValue(response.body(), MAP_TYPE);
                } catch (JsonProcessingException invalidJson) {
                    return failureResponse("AI returned invalid JSON.", LlmFailureType.INVALID_OUTPUT);
                }
                return parseProviderResponse(responseBody, resolvedModel, providerCode);
            } catch (Exception exception) {
                lastException = exception;
                if (attempt < retries) {
                    long waitMs = 1000L * (1 << attempt);
                    log.warn("LLM call attempt {}/{} failed; provider={}, model={}, type={}, retryMs={}",
                            attempt + 1, retries + 1, providerCode, resolvedModel,
                            classifyFailure(exception), waitMs);
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        LlmFailureType failureType = classifyFailure(lastException);
        log.error("LLM call failed; provider={}, model={}, retries={}, type={}",
                providerCode, resolvedModel, retries, failureType);
        return failureResponse("AI service unavailable.", failureType);
    }

    private ChatResponse call(RestTemplate client, String apiUrl, String apiKey,
                              String model, String systemPrompt, String userPrompt,
                              String providerCode, int retries) {
        return call(client, apiUrl, apiKey, model, systemPrompt, userPrompt,
                providerCode, retries, null);
    }

    private Map<String, Object> requestBody(String model, String systemPrompt, String userPrompt,
                                            String apiUrl, String providerCode,
                                            Map<String, Object> responseFormat) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("temperature", temperature);
        if (maxOutputTokens > 0) {
            body.put("max_tokens", maxOutputTokens);
        }
        if (isLocalOllama(apiUrl, providerCode)) {
            body.put("keep_alive", localKeepAlive);
        }
        if (isDeepSeekCloud(apiUrl, providerCode)) {
            body.put("thinking", Map.of(
                    "type", deepSeekThinkingEnabled ? "enabled" : "disabled"));
        }
        if (responseFormat != null) {
            body.put("response_format", responseFormat);
        }
        body.put("messages", Arrays.asList(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        return body;
    }

    private ChatResponse parseProviderResponse(Map<String, Object> responseBody, String model,
                                               String providerCode) {
        if (responseBody == null || !responseBody.containsKey("choices")) {
            return failureResponse("AI returned unexpected response format.",
                    LlmFailureType.INVALID_OUTPUT);
        }
        Object choicesValue = responseBody.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()
                || !(choices.get(0) instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)) {
            return failureResponse("AI returned unexpected response format.",
                    LlmFailureType.INVALID_OUTPUT);
        }
        Object contentValue = message.get("content");
        if (contentValue != null && !(contentValue instanceof String)) {
            return failureResponse("AI returned unexpected response format.",
                    LlmFailureType.INVALID_OUTPUT);
        }
        String content = (String) contentValue;
        int inputTokens = 0;
        int outputTokens = 0;
        Object usageValue = responseBody.get("usage");
        if (usageValue != null) {
            if (!(usageValue instanceof Map<?, ?> usage)) {
                return failureResponse("AI returned unexpected response format.",
                        LlmFailureType.INVALID_OUTPUT);
            }
            Object promptTokens = usage.get("prompt_tokens");
            Object completionTokens = usage.get("completion_tokens");
            if (promptTokens != null && !(promptTokens instanceof Number)
                    || completionTokens != null && !(completionTokens instanceof Number)) {
                return failureResponse("AI returned unexpected response format.",
                        LlmFailureType.INVALID_OUTPUT);
            }
            inputTokens = promptTokens == null ? 0 : ((Number) promptTokens).intValue();
            outputTokens = completionTokens == null ? 0 : ((Number) completionTokens).intValue();
        }
        if (content == null || content.isBlank()) {
            log.warn("LLM returned empty content: provider={}, model={}, finishReason={}, outputTokens={}",
                    providerCode, model, choice.get("finish_reason"), outputTokens);
            ChatResponse invalid = new ChatResponse(content, false, model, providerCode,
                    inputTokens, outputTokens);
            invalid.setFailureType(LlmFailureType.INVALID_OUTPUT);
            return invalid;
        }
        return new ChatResponse(content, true, model, providerCode, inputTokens, outputTokens);
    }

    private ChatResponse call(RestTemplate client, String apiUrl, String apiKey,
                              String model, String systemPrompt, String userPrompt,
                              String providerCode, int retries,
                              Map<String, Object> responseFormat) {
        String m = model != null ? model : "gpt-4o-mini";
        String sp = systemPrompt != null ? systemPrompt : defaultSystemPrompt;

        Exception lastException = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                Map<String, Object> body = new HashMap<>();
                body.put("model", m);
                body.put("temperature", temperature);
                if (maxOutputTokens > 0) body.put("max_tokens", maxOutputTokens);
                if (isLocalOllama(apiUrl, providerCode)) {
                    body.put("keep_alive", localKeepAlive);
                }
                if (isDeepSeekCloud(apiUrl, providerCode)) {
                    body.put("thinking", Map.of(
                        "type", deepSeekThinkingEnabled ? "enabled" : "disabled"));
                }
                if (responseFormat != null) body.put("response_format", responseFormat);
                body.put("messages", Arrays.asList(
                    Map.of("role", "system", "content", sp),
                    Map.of("role", "user", "content", userPrompt)
                ));

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                if (apiKey != null && !apiKey.isEmpty()) {
                    headers.setBearerAuth(apiKey);
                }

                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
                ResponseEntity<Map> response = client.exchange(
                    apiUrl, HttpMethod.POST, entity, Map.class);

            Map<String, Object> resp = response.getBody();
            if (resp != null && resp.containsKey("choices")) {
                Object choicesValue = resp.get("choices");
                if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()
                        || !(choices.get(0) instanceof Map<?, ?> choice)
                        || !(choice.get("message") instanceof Map<?, ?> message)) {
                    return failureResponse("AI returned unexpected response format.",
                            LlmFailureType.INVALID_OUTPUT);
                }
                Object contentValue = message.get("content");
                if (contentValue != null && !(contentValue instanceof String)) {
                    return failureResponse("AI returned unexpected response format.",
                            LlmFailureType.INVALID_OUTPUT);
                }
                String content = (String) contentValue;

                int inTokens = 0;
                int outTokens = 0;
                Object usageValue = resp.get("usage");
                if (usageValue != null) {
                    if (!(usageValue instanceof Map<?, ?> usage)) {
                        return failureResponse("AI returned unexpected response format.",
                                LlmFailureType.INVALID_OUTPUT);
                    }
                    Object promptTokens = usage.get("prompt_tokens");
                    Object completionTokens = usage.get("completion_tokens");
                    if (promptTokens != null && !(promptTokens instanceof Number)
                            || completionTokens != null && !(completionTokens instanceof Number)) {
                        return failureResponse("AI returned unexpected response format.",
                                LlmFailureType.INVALID_OUTPUT);
                    }
                    inTokens = promptTokens == null ? 0 : ((Number) promptTokens).intValue();
                    outTokens = completionTokens == null ? 0 : ((Number) completionTokens).intValue();
                }
                if (content == null || content.isBlank()) {
                    log.warn("LLM returned empty content: provider={}, model={}, finishReason={}, outputTokens={}",
                            providerCode, m, choice.get("finish_reason"), outTokens);
                    ChatResponse invalid = new ChatResponse(content, false, m, providerCode,
                            inTokens, outTokens);
                    invalid.setFailureType(LlmFailureType.INVALID_OUTPUT);
                    return invalid;
                }
                return new ChatResponse(content, true, m, providerCode, inTokens, outTokens);
            }
                // 响应格式异常，重试无意义
                return failureResponse("AI returned unexpected response format.",
                    LlmFailureType.INVALID_OUTPUT);
            } catch (Exception e) {
                lastException = e;
                if (attempt < retries) {
                    long waitMs = 1000L * (1 << attempt); // 1s, 2s, 4s...
                    log.warn("LLM call attempt {}/{} failed; provider={}, model={}, type={}, retryMs={}",
                            attempt + 1, retries + 1, providerCode, m,
                            classifyFailure(e), waitMs);
                    try { Thread.sleep(waitMs); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); break;
                    }
                }
            }
        }
        LlmFailureType failureType = classifyFailure(lastException);
        log.error("LLM call failed; provider={}, model={}, retries={}, type={}",
                providerCode, m, retries, failureType);
        return failureResponse("AI service unavailable.", failureType);
    }

    private ChatResponse failureResponse(String message, LlmFailureType failureType) {
        ChatResponse response = new ChatResponse(message, false);
        response.setFailureType(failureType);
        return response;
    }

    private LlmFailureType classifyFailure(Exception exception) {
        if (exception instanceof HttpStatusCodeException statusException) {
            return classifyHttpFailure(statusException.getStatusCode().value(),
                    statusException.getResponseBodyAsString());
        }
        Throwable current = exception;
        while (current != null) {
            if (current instanceof JsonProcessingException
                    || current.getClass().getName().contains("HttpMessageNotReadableException")) {
                return LlmFailureType.INVALID_OUTPUT;
            }
            String message = current.getMessage();
            if (current instanceof java.net.SocketTimeoutException
                    || current instanceof TimeoutException
                    || message != null && message.toLowerCase(Locale.ROOT).contains("timed out")) {
                return LlmFailureType.TIMEOUT;
            }
            current = current.getCause();
        }
        return LlmFailureType.MODEL_UNAVAILABLE;
    }

    private LlmFailureType classifyHttpFailure(int status, String responseBody) {
        if (status == 429 || containsQuotaExhaustion(responseBody)) {
            return LlmFailureType.RATE_LIMIT;
        }
        if (status >= 500) {
            return LlmFailureType.SERVER_ERROR;
        }
        if (status >= 400 && containsSchemaCompatibilityError(responseBody)) {
            return LlmFailureType.SCHEMA_UNSUPPORTED;
        }
        if (status == 404 || containsModelUnavailableError(responseBody)) {
            return LlmFailureType.MODEL_UNAVAILABLE;
        }
        return LlmFailureType.CLIENT_ERROR;
    }

    private boolean containsSchemaCompatibilityError(String responseBody) {
        String body = responseBody == null ? "" : responseBody.toLowerCase(Locale.ROOT);
        return body.contains("json_schema") || body.contains("response_format")
                || body.contains("uniqueitems")
                || body.contains("schema") && body.contains("unsupported");
    }

    private boolean containsQuotaExhaustion(String responseBody) {
        String body = responseBody == null ? "" : responseBody.toLowerCase(Locale.ROOT);
        return body.contains("allocationquota") || body.contains("quota exhausted")
                || body.contains("free tier only");
    }

    private boolean containsModelUnavailableError(String responseBody) {
        String body = responseBody == null ? "" : responseBody.toLowerCase(Locale.ROOT);
        return body.contains("model_not_found") || body.contains("model not found")
                || body.contains("model does not exist") || body.contains("unknown model")
                || body.contains("model unavailable") || body.contains("model is unavailable");
    }

    public String getDefaultSystemPrompt() { return defaultSystemPrompt; }

    /** Loads an Ollama model without generating a response and keeps it resident. */
    public boolean warmupLocalModel(String apiUrl, String model, String providerCode) {
        if (!isLocalOllama(apiUrl, providerCode) || model == null || model.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", Collections.emptyList());
            body.put("stream", false);
            body.put("keep_alive", localKeepAlive);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            rest.exchange(ollamaChatUrl(apiUrl), HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
            return true;
        } catch (Exception e) {
            log.warn("Local model warmup failed for {}: {}", model, e.getMessage());
            return false;
        }
    }

    private boolean isLocalOllama(String apiUrl, String providerCode) {
        String provider = providerCode == null ? "" : providerCode.toLowerCase(Locale.ROOT);
        String url = apiUrl == null ? "" : apiUrl.toLowerCase(Locale.ROOT);
        return provider.contains("ollama") || url.contains(":11434/");
    }

    private boolean isDeepSeekCloud(String apiUrl, String providerCode) {
        String provider = providerCode == null ? "" : providerCode.toLowerCase(Locale.ROOT);
        String url = apiUrl == null ? "" : apiUrl.toLowerCase(Locale.ROOT);
        return provider.contains("deepseek") || url.contains("api.deepseek.com");
    }

    private String ollamaChatUrl(String apiUrl) {
        String normalized = apiUrl == null ? "" : apiUrl.trim().replaceAll("/+$", "");
        if (normalized.endsWith("/v1/chat/completions")) {
            normalized = normalized.substring(0,
                normalized.length() - "/v1/chat/completions".length());
        } else if (normalized.endsWith("/api/chat")) {
            return normalized;
        }
        return normalized + "/api/chat";
    }
}
