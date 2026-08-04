package com.feisheng.bot.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlainTextReplyFormatterTest {
    @Test
    void removesMarkdownFormattingFromCustomerServiceReply() {
        String markdown = """
            点签主要通过以下方式使用：

            - **在钉钉或企业微信内**：添加点签应用。
            - **在微信公众号内**：搜索并关注点签。
            """;

        assertEquals("""
            点签主要通过以下方式使用：

            - 在钉钉或企业微信内：添加点签应用。
            - 在微信公众号内：搜索并关注点签。""",
            PlainTextReplyFormatter.format(markdown));
    }

    @Test
    void convertsCommonMarkdownToReadablePlainText() {
        String markdown = """
            ### 使用说明
            * 第一步
            > 注意事项
            访问[点签官网](https://example.com)，使用`OpenAPI`。
            """;

        assertEquals("""
            使用说明
            - 第一步
            注意事项
            访问点签官网（https://example.com），使用OpenAPI。""",
            PlainTextReplyFormatter.format(markdown));
    }

    @Test
    void removesDecorativeCustomerServiceIconsAndNormalizesDotBullets() {
        String decorated = """
            ✅ **点签支持多种使用渠道。**
            • 钉钉
            • 企业微信
            ⚠️ **注意**：不同渠道的入口不同。
            🔍 **下一步**：请说明您使用的渠道。
            """;

        assertEquals("""
            点签支持多种使用渠道。
            - 钉钉
            - 企业微信
            注意：不同渠道的入口不同。
            请说明您使用的渠道。""",
            PlainTextReplyFormatter.format(decorated));
    }

    @Test
    void removesNextStepLabelButKeepsGuidance() {
        String reply = "已为您确认可使用网页端办理。\n\n下一步：请登录后上传合同文件。";

        assertEquals("已为您确认可使用网页端办理。\n\n请登录后上传合同文件。",
            PlainTextReplyFormatter.format(reply));
    }

    @Test
    void preservesNextStepWhenItIsPartOfANormalSentence() {
        String reply = "下一步计划由人工客服与您确认。";

        assertEquals(reply, PlainTextReplyFormatter.format(reply));
    }

    @Test
    void preservesBusinessTermsAndLiteralAsterisks() {
        String plainText = "支持 APP、PC、OpenAPI、SaaS、H5；2 * 3 = 6；密码******；上传*.pdf。";

        assertEquals(plainText, PlainTextReplyFormatter.format(plainText));
    }

    @Test
    void handlesNullAndEmptyText() {
        assertNull(PlainTextReplyFormatter.format(null));
        assertEquals("", PlainTextReplyFormatter.format(""));
    }
}
