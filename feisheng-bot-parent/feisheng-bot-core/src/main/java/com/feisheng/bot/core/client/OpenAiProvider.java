package com.feisheng.bot.core.client;

import com.feisheng.bot.core.dto.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OpenAiProvider implements LlmProvider {
    private final LlmHttpClient llmClient;

    @Value("${ai.openai.url:https://api.openai.com/v1/chat/completions}")
    private String apiUrl;

    @Value("${ai.openai.key:}")
    private String apiKey;

    @Value("${ai.openai.model:gpt-4o-mini}")
    private String defaultModel;

    @Value("${ai.openai.system-prompt:You are a helpful customer service assistant.}")
    private String systemPrompt;

    public OpenAiProvider(LlmHttpClient llmClient) { this.llmClient = llmClient; }

    @Override
    public ChatResponse chat(String prompt, String model) {
        return chat(prompt, model, null);
    }

    @Override
    public ChatResponse chat(String prompt, String model, String customSystemPrompt) {
        return llmClient.call(apiUrl, apiKey,
            model != null ? model : defaultModel,
            customSystemPrompt != null ? customSystemPrompt : systemPrompt,
            prompt, "openai");
    }

    @Override public boolean isAvailable() { return !apiKey.isEmpty(); }
    @Override public String getProviderName() { return "openai"; }
}
