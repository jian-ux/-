package com.feisheng.bot.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RichReplyFormatterTest {
    @Test
    void keepsUsefulMarkdownAndRemovesUnsafeMarkupAndImages() {
        String formatted = RichReplyFormatter.format(
            "**结论**\n\n1. 先登录\n2. [官方入口](https://example.com/help)\n"
                + "[危险](javascript:alert(1)) ![模型图片](https://evil.example/x)"
                + "<script>alert(1)</script>");

        assertTrue(formatted.contains("**结论**"));
        assertTrue(formatted.contains("[官方入口](https://example.com/help)"));
        assertTrue(formatted.contains("危险"));
        assertFalse(formatted.contains("javascript:"));
        assertFalse(formatted.contains("![模型图片]"));
        assertFalse(formatted.contains("<script>"));
        assertTrue(RichReplyFormatter.isRich(formatted));
    }

    @Test
    void treatsPlainTextAsNonRich() {
        assertEquals("普通回答", RichReplyFormatter.format("普通回答"));
        assertFalse(RichReplyFormatter.isRich("普通回答"));
    }
}
