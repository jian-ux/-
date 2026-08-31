package com.feisheng.bot.core.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DialogResponseMetadataTest {
    @Test
    void appliesSharedValuesWithoutChangingExistingResponseKeys() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", "answer");
        response.put("answerStatus", "answered");
        DialogResponseMetadata metadata = new DialogResponseMetadata(
            Map.of("answerStatus", "answered", "source", "faq"));

        metadata.applyTo(response);

        assertEquals("answer", response.get("reply"));
        assertEquals("answered", response.get("answerStatus"));
        assertEquals("faq", response.get("source"));
        assertThrows(UnsupportedOperationException.class,
            () -> metadata.asMap().put("reply", "changed"));
    }
}
