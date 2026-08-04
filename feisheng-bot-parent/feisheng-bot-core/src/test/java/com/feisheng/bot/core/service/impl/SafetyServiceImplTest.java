package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.core.dto.SafetyResult;
import com.feisheng.bot.core.service.SafetyRuleProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SafetyServiceImplTest {
    @Test
    void normalizesFullWidthTextAndContinuesAfterLogOnlyRule() {
        SafetyRuleProvider ruleProvider = () -> List.of(
            rule("FORBIDDEN_TOPIC", "hello", "LOG_ONLY", 1),
            rule("SENSITIVE_WORD", "fuck", "BLOCK", 2)
        );
        @SuppressWarnings("unchecked")
        ObjectProvider<SafetyRuleProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenAnswer(ignored -> Stream.of(ruleProvider));

        SafetyResult result = new SafetyServiceImpl(providers).checkUserInput("hello ｆｕｃｋ");

        assertTrue(result.isBlocked());
        assertEquals("BLOCK", result.getAction());
    }

    private Map<String, Object> rule(String type, String pattern, String action, int priority) {
        return Map.of(
            "ruleType", type,
            "pattern", pattern,
            "action", action,
            "isEnabled", 1,
            "isRegex", 0,
            "priority", priority,
            "description", type + " rule"
        );
    }
}
