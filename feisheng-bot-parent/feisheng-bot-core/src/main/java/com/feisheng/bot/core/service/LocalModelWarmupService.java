package com.feisheng.bot.core.service;

import com.feisheng.bot.core.client.AdminClient;
import com.feisheng.bot.core.client.LlmHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Preloads configured Ollama chat models so the first customer request is not a cold start. */
@Service
public class LocalModelWarmupService {
    private static final Logger log = LoggerFactory.getLogger(LocalModelWarmupService.class);

    private final AdminClient adminClient;
    private final LlmHttpClient llmHttpClient;

    @Value("${ai.llm.local.warmup-enabled:true}")
    private boolean warmupEnabled;

    public LocalModelWarmupService(AdminClient adminClient, LlmHttpClient llmHttpClient) {
        this.adminClient = adminClient;
        this.llmHttpClient = llmHttpClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        if (!warmupEnabled) return;
        Set<String> warmed = new HashSet<>();
        for (Map<String, Object> model : adminClient.getActiveModels()) {
            if (!isChatModel(model) || !isOllama(model)) continue;
            String apiUrl = string(model.get("apiUrl"));
            String modelName = string(model.get("modelName"));
            String provider = string(model.get("provider"));
            if (apiUrl.isBlank() || modelName.isBlank()
                    || !warmed.add(apiUrl + "|" + modelName)) continue;
            long started = System.nanoTime();
            boolean success = llmHttpClient.warmupLocalModel(apiUrl, modelName, provider);
            log.info("Local model warmup model={}, success={}, latencyMs={}",
                modelName, success, (System.nanoTime() - started) / 1_000_000L);
        }
    }

    private boolean isChatModel(Map<String, Object> model) {
        Object status = model.get("status");
        String type = string(model.get("modelType"));
        return status instanceof Number number && number.intValue() == 1
            && (type.isBlank() || "LLM".equalsIgnoreCase(type));
    }

    private boolean isOllama(Map<String, Object> model) {
        return string(model.get("provider")).toLowerCase().contains("ollama")
            || string(model.get("apiUrl")).contains(":11434/");
    }

    private String string(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
