package com.feisheng.bot.core.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, source-labelled customer context assembled for one request. */
public record CustomerContextSnapshot(
        CustomerProfileService.ProfileSnapshot profile,
        CustomerLongTermMemoryService.Snapshot longTermMemory,
        String historyContext,
        Map<String, Object> diagnostics) {

    public CustomerContextSnapshot {
        profile = profile == null ? CustomerProfileService.ProfileSnapshot.empty() : profile;
        longTermMemory = longTermMemory == null
                ? CustomerLongTermMemoryService.Snapshot.empty() : longTermMemory;
        historyContext = historyContext == null ? "" : historyContext;
        diagnostics = diagnostics == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics));
    }

    public static CustomerContextSnapshot empty() {
        return new CustomerContextSnapshot(null, null, "", Map.of());
    }

    public String profileContext(String question, CustomerProfileService service) {
        return service == null ? null : service.contextFor(question, profile);
    }

    public String longTermContext(String question, CustomerLongTermMemoryService service) {
        return service == null ? null : service.contextFor(question, longTermMemory).orElse(null);
    }
}
