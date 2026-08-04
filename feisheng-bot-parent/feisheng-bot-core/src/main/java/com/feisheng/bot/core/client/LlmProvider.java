
package com.feisheng.bot.core.client;

import com.feisheng.bot.core.dto.ChatResponse;

/**
 * LLM 供应商接口
 */
public interface LlmProvider {
    ChatResponse chat(String prompt, String model);
    default ChatResponse chat(String prompt, String model, String systemPrompt) {
        return chat(prompt, model);
    }
    boolean isAvailable();
    String getProviderName();
}
