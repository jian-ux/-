package com.feisheng.bot.core.client;

import com.feisheng.bot.core.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;

/**
 * LLM HTTP 调用客户端：统一处理 OpenAI 兼容 API 的调用、重试、响应解析
 */
@Component
public class LlmHttpClient {
    private static final Logger log = LoggerFactory.getLogger(LlmHttpClient.class);

    private RestTemplate rest;

    @Value("${ai.llm.connect-timeout:10000}")
    private int connectTimeout;

    @Value("${ai.llm.read-timeout:60000}")
    private int readTimeout;

    @Value("${ai.llm.max-retries:2}")
    private int maxRetries;

    @Value("${ai.llm.temperature:0.0}")
    private double temperature;

    @Value("${ai.llm.system-prompt:You are a helpful customer service assistant.}")
    private String defaultSystemPrompt;

    @PostConstruct
    public void init() {
        this.rest = restTemplate(readTimeout);
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
        return call(restTemplate(requestReadTimeout), apiUrl, apiKey, model,
            systemPrompt, userPrompt, providerCode, Math.max(0, requestMaxRetries));
    }

    private ChatResponse call(RestTemplate client, String apiUrl, String apiKey,
                              String model, String systemPrompt, String userPrompt,
                              String providerCode, int retries) {
        return call(client, apiUrl, apiKey, model, systemPrompt, userPrompt,
            providerCode, retries, null);
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
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
                    if (!choices.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                        String content = (String) message.get("content");

                        int inTokens = 0, outTokens = 0;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> usage = (Map<String, Object>) resp.get("usage");
                        if (usage != null) {
                            if (usage.get("prompt_tokens") != null)
                                inTokens = ((Number) usage.get("prompt_tokens")).intValue();
                            if (usage.get("completion_tokens") != null)
                                outTokens = ((Number) usage.get("completion_tokens")).intValue();
                        }
                        return new ChatResponse(content, true, m, providerCode, inTokens, outTokens);
                    }
                }
                // 响应格式异常，重试无意义
                return new ChatResponse("AI returned unexpected response format.", false);
            } catch (Exception e) {
                lastException = e;
                if (attempt < retries) {
                    long waitMs = 1000L * (1 << attempt); // 1s, 2s, 4s...
                    log.warn("LLM call attempt {}/{} failed, retrying in {}ms: {}",
                        attempt + 1, retries + 1, waitMs, e.getMessage());
                    try { Thread.sleep(waitMs); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); break;
                    }
                }
            }
        }
        log.error("LLM call failed after {} retries: {}", retries,
            lastException != null ? lastException.getMessage() : "unknown");
        return new ChatResponse("AI service unavailable.", false);
    }

    public String getDefaultSystemPrompt() { return defaultSystemPrompt; }
}
