package com.feisheng.bot.core.client;

import com.feisheng.bot.core.dto.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmRouterTest {
    @Test
    void passesCustomSystemPromptToFallbackProvider() {
        RecordingProvider provider = new RecordingProvider();
        LlmRouter router = new LlmRouter(List.of(provider));

        ChatResponse response = router.chat("用户问题", null, "客服规则");

        assertEquals("客服规则", provider.systemPrompt);
        assertEquals("模型回答", response.getContent());
    }

    private static class RecordingProvider implements LlmProvider {
        private String systemPrompt;

        @Override
        public ChatResponse chat(String prompt, String model) {
            return new ChatResponse("旧调用", true);
        }

        @Override
        public ChatResponse chat(String prompt, String model, String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return new ChatResponse("模型回答", true);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
