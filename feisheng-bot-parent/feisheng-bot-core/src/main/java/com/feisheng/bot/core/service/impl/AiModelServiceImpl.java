package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.core.client.AdminClient;
import com.feisheng.bot.core.client.LlmHttpClient;
import com.feisheng.bot.core.client.LlmRouter;
import com.feisheng.bot.core.dto.ChatResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class AiModelServiceImpl {
    private static final Logger log = LoggerFactory.getLogger(AiModelServiceImpl.class);
    private final LlmRouter llmRouter;
    private final AdminClient adminClient;
    private final LlmHttpClient llmClient;

    public AiModelServiceImpl(LlmRouter llmRouter, AdminClient adminClient, LlmHttpClient llmClient) {
        this.llmRouter = llmRouter;
        this.adminClient = adminClient;
        this.llmClient = llmClient;
    }

    /**
     * Chat with default system prompt (from LlmHttpClient config).
     */
    public ChatResponse chat(String prompt) {
        return chat(prompt, null);
    }

    /**
     * Chat with a custom system prompt.
     * If systemPrompt is null, uses the default from LlmHttpClient.
     */
    @CircuitBreaker(name = "aiModel", fallbackMethod = "chatFallback")
    public ChatResponse chat(String prompt, String systemPrompt) {
        return chatConfigured(prompt, systemPrompt, null);
    }

    /** Use the selected DB model first, then retain the normal fallback order. */
    @CircuitBreaker(name = "aiModel", fallbackMethod = "chatPreferredFallback")
    public ChatResponse chatWithModel(String prompt, String systemPrompt, Long preferredModelId) {
        return chatConfigured(prompt, systemPrompt, preferredModelId);
    }

    private ChatResponse chatConfigured(String prompt, String systemPrompt, Long preferredModelId) {
        String sp = systemPrompt != null ? systemPrompt : llmClient.getDefaultSystemPrompt();

        // 1. Try DB-configured active models
        List<Map<String, Object>> activeModels = adminClient.getActiveModels();
        if (activeModels != null && !activeModels.isEmpty()) {
            List<Map<String, Object>> orderedModels = preferredModelId == null
                ? activeModels
                : Stream.concat(
                    activeModels.stream().filter(model -> preferredModelId.equals(longValue(model.get("id")))),
                    activeModels.stream().filter(model -> !preferredModelId.equals(longValue(model.get("id")))))
                    .toList();
            for (Map<String, Object> model : orderedModels) {
                int status = numberValue(model.get("status"), 0);
                String modelType = stringValue(model.get("modelType"));
                if (status == 1 && (modelType.isBlank() || "LLM".equalsIgnoreCase(modelType))) {
                    String apiUrl = stringValue(model.get("apiUrl"));
                    String apiKey = stringValue(model.get("apiKey"));
                    String modelName = stringValue(model.get("modelName"));
                    if (apiUrl != null && !apiUrl.isEmpty() && apiKey != null && !apiKey.isEmpty()) {
                        log.info("Using DB-configured model: {}", modelName);
                        ChatResponse resp = llmClient.call(apiUrl, apiKey, modelName, sp, prompt, "db");
                        if (resp.isSuccess()) return resp;
                        log.warn("DB model {} failed, trying next", modelName);
                    }
                }
            }
        }
        // 2. Fall back to yml-configured providers via LlmRouter
        return llmRouter.chat(prompt, null, sp);
    }

    public ChatResponse chat(String prompt, String preferredProvider, String systemPrompt) {
        return llmRouter.chat(prompt, preferredProvider, systemPrompt);
    }

    public ChatResponse chatFallback(String prompt, String systemPrompt, Throwable t) {
        return fallbackResponse(t);
    }

    public ChatResponse chatPreferredFallback(String prompt, String systemPrompt,
                                              Long preferredModelId, Throwable t) {
        return fallbackResponse(t);
    }

    private ChatResponse fallbackResponse(Throwable t) {
        log.warn("AI model circuit breaker triggered, fallback: {}", t.getMessage());
        ChatResponse resp = new ChatResponse();
        resp.setContent("抱歉，AI服务暂时不可用，请稍后再试。");
        resp.setSuccess(false);
        resp.setModel("fallback");
        resp.setProviderCode("fallback");
        return resp;
    }

    private static int numberValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
