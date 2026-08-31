package com.feisheng.bot.core.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Request-local cache for repeated sensitive-data redaction. */
public final class RedactionMemoizer {
    private final SensitiveDataService sensitiveDataService;
    private final Map<String, SensitiveDataService.RedactionResult> cache = new HashMap<>();

    public RedactionMemoizer(SensitiveDataService sensitiveDataService) {
        this.sensitiveDataService = sensitiveDataService;
    }

    public String redact(String value, Set<String> redactedTypes) {
        SensitiveDataService.RedactionResult result = cache.computeIfAbsent(
            value, sensitiveDataService::redact);
        if (redactedTypes != null) {
            redactedTypes.addAll(result.types());
        }
        return result.text();
    }
}
