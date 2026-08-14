package com.feisheng.bot.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerServicePromptProviderTest {
    @Test
    void keepsV1AsDefaultAndUsesConfiguredLegacyPrompt() {
        CustomerServicePromptProvider provider =
            new CustomerServicePromptProvider("v1", "legacy prompt");

        assertEquals("v1", provider.resolveVersion(null));
        assertEquals("legacy prompt", provider.promptFor("v1"));
    }

    @Test
    void providesV2WithDecisionSignalsAndWithoutUnverifiedExamples() {
        CustomerServicePromptProvider provider =
            new CustomerServicePromptProvider("v1", "legacy prompt");

        String prompt = provider.promptFor("V2");

        assertTrue(prompt.contains("点签电子合同"));
        assertTrue(prompt.contains("__ANSWER_PARTIAL__"));
        assertTrue(prompt.contains("__NO_ANSWER__"));
        assertTrue(prompt.contains("期限、数量、阈值"));
        assertTrue(prompt.contains("总数与随后明确列出的项目数量不一致"));
        assertTrue(prompt.contains("首句必须先明确该直接操作是否允许"));
        assertTrue(prompt.contains("多个并列问题"));
        assertTrue(prompt.contains("不得因为其中一项缺少依据而拒答全部问题"));
        assertTrue(prompt.contains("自动/手动"));
        assertTrue(prompt.contains("禁止颠倒结论"));
        assertTrue(prompt.contains("答完即止"));
        assertTrue(!prompt.contains("80%"));
        assertTrue(!prompt.contains("12小时"));
        assertTrue(!prompt.contains("三种方式"));
    }

    @Test
    void rejectsUnsupportedVersions() {
        assertThrows(IllegalArgumentException.class,
            () -> new CustomerServicePromptProvider("v3", "legacy prompt"));
    }
}
