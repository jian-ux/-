package com.feisheng.bot.core.client;

import com.feisheng.bot.core.dto.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekProvider implements LlmProvider {
    private final LlmHttpClient llmClient;

    @Value("${ai.deepseek.url:https://api.deepseek.com/v1/chat/completions}")
    private String apiUrl;

    @Value("${ai.deepseek.key:}")
    private String apiKey;

    @Value("${ai.deepseek.model:deepseek-chat}")
    private String defaultModel;

    @Value("${ai.deepseek.system-prompt:你是智能客服助手，请用中文回答。}")
    private String systemPrompt;

    public DeepSeekProvider(LlmHttpClient llmClient) { this.llmClient = llmClient; }

    @Override
    public ChatResponse chat(String prompt, String model) {
        return chat(prompt, model, null);
    }

    @Override
    public ChatResponse chat(String prompt, String model, String customSystemPrompt) {
        return llmClient.call(apiUrl, apiKey,
            model != null ? model : defaultModel,
            customSystemPrompt != null ? customSystemPrompt : systemPrompt,
            prompt, "deepseek");
    }

    @Override public boolean isAvailable() { return !apiKey.isEmpty(); }
    @Override public String getProviderName() { return "deepseek"; }
}
