package com.feisheng.bot.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationSummaryFormatTest {
    private final ConversationSummaryFormat format = new ConversationSummaryFormat();

    @Test
    void acceptsOnlyCompleteFixedFormat() {
        String complete = completeSummary("接收方认证入口仍待确认");

        assertTrue(format.normalizeModelOutput(complete, 1000).isPresent());
        assertFalse(format.normalizeModelOutput(
            "客户身份：企业客户\n当前问题：认证流程", 1000).isPresent());
        assertFalse(format.normalizeModelOutput(
            complete.replace("客户身份：企业客户\n咨询产品：点签电子合同",
                "咨询产品：点签电子合同\n客户身份：企业客户"), 1000).isPresent());
    }

    @Test
    void preservesEveryFieldWhenApplyingLengthLimit() {
        String normalized = format.normalizeModelOutput(
            completeSummary("需要继续确认".repeat(40)), 180).orElseThrow();

        assertTrue(normalized.length() <= 180);
        assertEquals(8, normalized.lines().count());
        assertTrue(normalized.contains("当前未解决事项："));
    }

    @Test
    void migratesLegacyFreeTextWithoutTreatingItAsConfirmedFact() {
        String migrated = format.normalizeStoredSummary("客户想继续确认认证入口", 1000);

        assertTrue(migrated.contains("当前问题：客户想继续确认认证入口"));
        assertTrue(migrated.contains("已确认信息：未确认"));
    }

    private String completeSummary(String unresolved) {
        return """
            客户身份：企业客户
            咨询产品：点签电子合同
            套餐或版本：企业套餐
            当前问题：认证和签署流程
            已确认信息：客户使用网页端
            已给出的处理建议：先完成企业认证
            仍待确认信息：具体认证入口
            当前未解决事项：%s
            """.formatted(unresolved).strip();
    }
}
