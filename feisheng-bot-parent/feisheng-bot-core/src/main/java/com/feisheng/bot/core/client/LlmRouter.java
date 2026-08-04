
package com.feisheng.bot.core.client;

import com.feisheng.bot.core.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM 路由: 按配置选择可用供应商
 */
@Component
public class LlmRouter {
    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);
    private final List<LlmProvider> providers;

    public LlmRouter(List<LlmProvider> providers) {
        this.providers = providers;
    }

    public ChatResponse chat(String prompt) {
        return chat(prompt, null);
    }

    public ChatResponse chat(String prompt, String preferredProvider) {
        return chat(prompt, preferredProvider, null);
    }

    public ChatResponse chat(String prompt, String preferredProvider, String systemPrompt) {
        // 优先选择指定供应商
        if (preferredProvider != null) {
            for (LlmProvider p : providers) {
                if (p.getProviderName().equals(preferredProvider) && p.isAvailable()) {
                    log.info("Using preferred provider: {}", p.getProviderName());
                    return p.chat(prompt, null, systemPrompt);
                }
            }
        }

        // 按顺序选择第一个可用的
        for (LlmProvider p : providers) {
            if (p.isAvailable()) {
                log.info("Using provider: {}", p.getProviderName());
                return p.chat(prompt, null, systemPrompt);
            }
        }

        log.warn("No LLM provider available");
        return new ChatResponse("AI 服务未配置，请在后台配置模型后重试。", false);
    }
}
