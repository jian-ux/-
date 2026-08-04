package com.feisheng.bot.core.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Facade for model configuration supplied by the application hosting Core. */
@Component
public class AdminClient {
    private static final Logger log = LoggerFactory.getLogger(AdminClient.class);
    private final ObjectProvider<ModelConfigProvider> providers;

    public AdminClient(ObjectProvider<ModelConfigProvider> providers) {
        this.providers = providers;
    }

    public List<Map<String, Object>> getActiveModels() {
        ModelConfigProvider provider = providers.orderedStream().findFirst().orElse(null);
        if (provider == null) return Collections.emptyList();
        try {
            List<Map<String, Object>> models = provider.getActiveModels();
            return models == null ? Collections.emptyList() : models;
        } catch (Exception e) {
            log.warn("Failed to load runtime model configuration: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
