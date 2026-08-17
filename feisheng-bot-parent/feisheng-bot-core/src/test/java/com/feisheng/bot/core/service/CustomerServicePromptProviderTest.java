package com.feisheng.bot.core.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

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
    void preservesLegacyV1PropertyWhenTheNewV1PropertyIsEmpty() {
        CustomerServicePromptProvider provider =
            new CustomerServicePromptProvider("v1", "", "legacy prompt", "");

        assertEquals("legacy prompt", provider.promptFor("v1"));
        assertEquals("configured_v1", provider.sourceFor("v1"));
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
        assertTrue(prompt.contains("不得只给出笼统规则"));
        assertTrue(prompt.contains("必须紧接着说明该替代方式"));
        assertTrue(prompt.contains("不能把多个具体能力合并成一个笼统说法"));
        assertTrue(prompt.contains("答完即止"));
        assertTrue(!prompt.contains("80%"));
        assertTrue(!prompt.contains("12小时"));
        assertTrue(!prompt.contains("三种方式"));
        assertEquals("built_in_v2", provider.sourceFor("v2"));
    }

    @Test
    void usesConfiguredV2AndProvidesMandatoryPolicyAndStableFingerprint() {
        CustomerServicePromptProvider provider =
            new CustomerServicePromptProvider("v2", "configured v1", "configured v2");

        assertEquals("configured v2", provider.promptFor("v2"));
        assertEquals("configured_v2", provider.sourceFor("v2"));
        assertTrue(provider.mandatoryPolicy().contains("通用法律知识不得用于推导点签产品支持"));
        assertTrue(provider.mandatoryPolicy().contains("未获得业务工具成功结果"));
        assertTrue(provider.mandatoryPolicy().contains("咨询客服”替代已经提供的公开事实"));
        assertTrue(provider.mandatoryPolicy().contains("必须保留所有与当前问题直接相关的关键动作和能力"));
        assertEquals(CustomerServicePromptProvider.fingerprint("configured v2"),
            CustomerServicePromptProvider.fingerprint(provider.promptFor("v2")));
        assertEquals(64, CustomerServicePromptProvider.fingerprint("configured v2").length());
    }

    @Test
    void rejectsUnsupportedVersions() {
        assertThrows(IllegalArgumentException.class,
            () -> new CustomerServicePromptProvider("v3", "legacy prompt"));
    }

    @Test
    void springSelectsAutowiredConstructorAndResolvesLegacyAndV2Properties() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "test-properties", Map.of(
                    "ai.customer-service.prompt-version", "v2",
                    "ai.customer-service.system-prompt-v1", "",
                    "ai.customer-service.system-prompt-full", "legacy-from-environment",
                    "ai.customer-service.system-prompt-v2", "v2-from-environment")));
            context.register(CustomerServicePromptProvider.class);
            context.refresh();

            CustomerServicePromptProvider provider =
                context.getBean(CustomerServicePromptProvider.class);
            assertEquals("v2-from-environment", provider.promptFor("v2"));
            assertEquals("legacy-from-environment", provider.promptFor("v1"));
            assertEquals("configured_v2", provider.sourceFor("v2"));
            assertEquals("configured_v1", provider.sourceFor("v1"));
        }
    }
}
