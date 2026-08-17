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
    void inheritsProductForFeatureFollowUp() {
        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "点签是什么？"),
            message("ai", "点签是一款电子合同应用。"),
            message("user", "有什么功能？")), "有什么功能？");

        assertTrue(result.contextDependent());
        assertTrue(result.rewritten());
        assertEquals("点签电子合同 有什么功能？", result.query());
        assertEquals("点签电子合同", result.inheritedProduct());
    }

    @Test
    void inheritsProductForUsageChannelFollowUp() {
        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "点签是什么？"),
            message("ai", "点签是一款电子合同应用。"),
            message("user", "可以在哪里使用？")), "可以在哪里使用？");

        assertEquals("点签电子合同 可以在哪里使用？", result.query());
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

    @Test
    void carriesHistoryForAnaphoricAttachmentFollowUp() {
        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "合同双方都已经签署完成了。"),
            message("ai", "已完成签署的合同内容通常不能直接修改。"),
            message("user", "那漏掉的附件怎么办？")), "那漏掉的附件怎么办？");

        assertTrue(result.contextDependent());
        assertEquals("合同双方都已经签署完成了。", result.previousQuestion());
    }

    @Test
    void mergesPreviousContractScenarioIntoAnaphoricFollowUp() {
        String previousQuestion = "我刚发起一份合同，发现正文写错了，对方还没有签。";
        String currentQuestion = "这个还能直接改吗？";

        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", previousQuestion),
            message("user", currentQuestion)), currentQuestion);

        assertTrue(result.contextDependent());
        assertTrue(result.rewritten());
        assertEquals(previousQuestion + " " + currentQuestion, result.query());
        assertEquals(previousQuestion, result.previousQuestion());
    }

    @Test
    void recognizesAndMergesStateContinuationPhoneFollowUp() {
        String previousQuestion = "合同已经发出，状态是待签署，但接收人的手机号填错了。";
        String currentQuestion = "现在还能把号码改掉吗？";

        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", previousQuestion),
            message("ai", "请问您想确认能否直接修改接收人信息吗？"),
            message("user", currentQuestion)), currentQuestion);

        assertTrue(result.contextDependent());
        assertTrue(result.rewritten());
        assertEquals(previousQuestion + " " + currentQuestion, result.query());
        assertEquals(previousQuestion, result.previousQuestion());
    }

    @Test
    void doesNotInventContextForStateContinuationWithoutPreviousUserTurn() {
        String question = "现在还能把号码改掉吗？";

        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(), question);

        assertTrue(result.contextDependent());
        assertFalse(result.rewritten());
        assertEquals(question, result.query());
    }

    private BotMessage message(String role, String content) {
        BotMessage message = new BotMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
