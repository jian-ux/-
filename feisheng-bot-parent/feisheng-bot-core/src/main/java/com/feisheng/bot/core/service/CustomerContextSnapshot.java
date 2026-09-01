package com.feisheng.bot.core.service;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, source-labelled customer context assembled for one request. */
public record CustomerContextSnapshot(
        CustomerProfileService.ProfileSnapshot profile,
        CustomerLongTermMemoryService.Snapshot longTermMemory,
        String historyContext,
        Map<String, Object> diagnostics,
        List<ContextRecord> contextRecords) {

    public CustomerContextSnapshot {
        profile = profile == null ? CustomerProfileService.ProfileSnapshot.empty() : profile;
        longTermMemory = longTermMemory == null
                ? CustomerLongTermMemoryService.Snapshot.empty() : longTermMemory;
        historyContext = historyContext == null ? "" : historyContext;
        diagnostics = diagnostics == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics));
        contextRecords = contextRecords == null ? List.of() : List.copyOf(contextRecords);
    }

    public CustomerContextSnapshot(CustomerProfileService.ProfileSnapshot profile,
                                   CustomerLongTermMemoryService.Snapshot longTermMemory,
                                   String historyContext, Map<String, Object> diagnostics) {
        this(profile, longTermMemory, historyContext, diagnostics, List.of());
    }

    public static CustomerContextSnapshot empty() {
        return new CustomerContextSnapshot(null, null, "", Map.of(), List.of());
    }

    public String profileContext(String question, CustomerProfileService service) {
        return service == null ? null : service.contextFor(question, profile);
    }

    public String longTermContext(String question, CustomerLongTermMemoryService service) {
        return service == null ? null : service.contextFor(question, longTermMemory).orElse(null);
    }

    /** Source metadata retained for selector audit; legacy sources can leave IDs absent. */
    public record ContextRecord(String contextId, String sourceType, String content, Long customerId,
                                Long sessionId, String channelType, String channelUserId, Double confidence,
                                Long messageId, Date createdAt, Date expiresAt, String reason) {
        public ContextRecord {
            createdAt = createdAt == null ? null : new Date(createdAt.getTime());
            expiresAt = expiresAt == null ? null : new Date(expiresAt.getTime());
        }

        @Override
        public Date createdAt() {
            return createdAt == null ? null : new Date(createdAt.getTime());
        }

        @Override
        public Date expiresAt() {
            return expiresAt == null ? null : new Date(expiresAt.getTime());
        }
    }
}
