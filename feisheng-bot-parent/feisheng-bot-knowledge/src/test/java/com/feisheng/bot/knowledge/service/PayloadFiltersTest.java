package com.feisheng.bot.knowledge.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadFiltersTest {
    @Test
    void dottedKeysTraverseNestedPayloadLikeQdrant() {
        Map<String, Object> payload = Map.of(
            "metadata", Map.of("risk_level", "HIGH"));

        assertTrue(PayloadFilters.matchesPayload(payload,
            Map.of("metadata.risk_level", "HIGH")));
        assertFalse(PayloadFilters.matchesPayload(payload,
            Map.of("metadata.risk_level", "LOW")));
        assertFalse(PayloadFilters.matchesPayload(payload,
            Map.of("metadata.missing", "HIGH")));
    }
}
