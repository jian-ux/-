package com.feisheng.bot.core.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactionMemoizerTest {
    @Test
    void cachesRepeatedValueAndAccumulatesTypes() {
        RedactionMemoizer memoizer = new RedactionMemoizer(new SensitiveDataService(""));
        Set<String> types = new LinkedHashSet<>();

        assertEquals("手机号：[手机号已脱敏]", memoizer.redact("手机号：13800138000", types));
        assertEquals("手机号：[手机号已脱敏]", memoizer.redact("手机号：13800138000", types));
        memoizer.redact("邮箱：a@example.com", types);

        assertEquals(Set.of("PHONE", "EMAIL"), types);
    }

    @Test
    void doesNotLeakValuesAcrossRequestMemoizers() {
        AtomicInteger calls = new AtomicInteger();
        SensitiveDataService service = new SensitiveDataService("") {
            @Override
            public RedactionResult redact(String value) {
                calls.incrementAndGet();
                return super.redact(value);
            }
        };
        RedactionMemoizer first = new RedactionMemoizer(service);
        RedactionMemoizer second = new RedactionMemoizer(service);

        assertTrue(first.redact("13800138000", new LinkedHashSet<>()).contains("手机号已脱敏"));
        assertTrue(second.redact("13800138000", new LinkedHashSet<>()).contains("手机号已脱敏"));
        assertEquals(2, calls.get());
    }
}
