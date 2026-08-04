package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.core.entity.BotMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextualQueryResolverTest {
    private final ContextualQueryResolver resolver = new ContextualQueryResolver();

    @Test
    void carriesContractTopicWhenSubjectChangesToEnterprise() {
        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "我要怎么签合同"),
            message("ai", "可以手写签名或使用电子签名。"),
            message("user", "企业的呢？")), "企业的呢？");

        assertTrue(result.contextDependent());
        assertTrue(result.rewritten());
        assertEquals("企业怎么签合同", result.query());
    }

    @Test
    void keepsMembershipTopicWhenSubjectChangesToEnterprise() {
        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "个人会员有哪些？"),
            message("ai", "个人会员分为多个类型。"),
            message("user", "企业的呢？")), "企业的呢？");

        assertEquals("企业会员有哪些？", result.query());
    }

    @Test
    void replacesAccessChannelWithoutChangingOperation() {
        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "微信端怎么发起合同？"),
            message("ai", "进入合同发起页面。"),
            message("user", "PC端呢？")), "PC端呢？");

        assertEquals("PC端怎么发起合同？", result.query());
    }

    @Test
    void inheritsProductForOperationalQuestionThatOmitsIt() {
        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "我在你们点签电子合同，购买的合同如何查询是否被篡改？"),
            message("ai", "可以登录点签官网验签页面上传文件进行验签。"),
            message("user", "企业怎么登录？")), "企业怎么登录？");

        assertTrue(result.contextDependent());
        assertTrue(result.rewritten());
        assertEquals("点签电子合同 企业怎么登录？", result.query());
        assertEquals("点签电子合同", result.inheritedProduct());
    }

    @Test
    void keepsActiveProductAcrossOneFailedAnswerTurn() {
        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "我在你们点签电子合同，购买的合同如何查询是否被篡改？"),
            message("ai", "可以登录点签官网验签页面上传文件进行验签。"),
            message("user", "企业怎么登录？"),
            message("ai", "这个问题暂时无法准确确认。"),
            message("user", "企业怎么登录？")), "企业怎么登录？");

        assertEquals("点签电子合同 企业怎么登录？", result.query());
        assertEquals("点签电子合同", result.inheritedProduct());
    }

    @Test
    void doesNotCarryProductIntoUnrelatedStandaloneQuestion() {
        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "点签电子合同怎么签？"),
            message("ai", "可以在线发起和签署合同。"),
            message("user", "南京明天天气怎么样？")), "南京明天天气怎么样？");

        assertFalse(result.contextDependent());
        assertEquals("南京明天天气怎么样？", result.query());
    }

    @Test
    void leavesStandaloneQuestionUntouched() {
        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "个人会员有哪些？"),
            message("ai", "个人会员分为多个类型。"),
            message("user", "电子合同有法律效力吗？")), "电子合同有法律效力吗？");

        assertFalse(result.contextDependent());
        assertFalse(result.rewritten());
        assertEquals("电子合同有法律效力吗？", result.query());
    }

    private BotMessage message(String role, String content) {
        BotMessage message = new BotMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
