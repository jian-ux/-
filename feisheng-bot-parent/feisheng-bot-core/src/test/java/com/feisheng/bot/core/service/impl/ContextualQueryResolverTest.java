package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.core.entity.BotMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextualQueryResolverTest {
    private final ContextualQueryResolver resolver =
        new ContextualQueryResolver(new ObjectMapper());

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
    void rewritesDianqianSelectionAfterCaClarification() {
        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "CA证书怎么申请？"),
            message("ai", "您是咨询翔晟CA吗还是点签电子合同平台呢？"),
            message("user", "点签电子合同")), "点签电子合同");

        assertTrue(result.clarificationResolved());
        assertTrue(result.rewritten());
        assertEquals("CA证书怎么申请？ 点签电子合同", result.query());
        assertEquals("CA证书怎么申请？", result.previousQuestion());
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
    void recognizesSubjectlessLoginAsAContextualOperationalQuestion() {
        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "点签电子合同怎么使用？"),
            message("ai", "可以通过网页端使用。"),
            message("user", "怎么登录？")), "怎么登录？");

        assertTrue(result.contextDependent());
        assertTrue(result.rewritten());
        assertEquals("点签电子合同 怎么登录？", result.query());
    }

    @ParameterizedTest
    @ValueSource(strings = {"那费用呢？", "价格呢？", "多少钱一份？", "套餐多少钱？"})
    void mergesShortAttributeFollowUpsWithThePreviousBusinessScenario(
            String currentQuestion) {
        String previousQuestion = "点签电子合同的标准套餐怎么购买？";

        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", previousQuestion),
            message("ai", "可以按企业签署量购买套餐。"),
            message("user", currentQuestion)), currentQuestion);

        assertTrue(result.contextDependent());
        assertTrue(result.rewritten());
        assertTrue(result.previousQuestionMerged());
        assertEquals(previousQuestion + " " + currentQuestion, result.query());
        assertEquals(previousQuestion, result.previousQuestion());
    }

    @Test
    void doesNotInventShortAttributeContextWithoutBusinessHistory() {
        String currentQuestion = "多少钱一份？";

        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "之前的问题已经结束了")), currentQuestion);

        assertTrue(result.contextDependent());
        assertFalse(result.rewritten());
        assertEquals(currentQuestion, result.query());
    }

    @Test
    void doesNotInventContextForSubjectlessOperationWithoutActiveProduct() {
        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "之前的问题已经结束了")), "怎么登录？");

        assertFalse(result.contextDependent());
        assertFalse(result.rewritten());
        assertEquals("怎么登录？", result.query());
    }

    @Test
    void inheritsPointSignWhenIntroductionReplyUsesOnlyTheProductCategory() {
        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "你们是做什么的？"),
            message("ai", "我们是电子合同平台，用户可全程在线上发起以及签署。"),
            message("user", "怎么使用？")), "怎么使用？");

        assertTrue(result.contextDependent());
        assertTrue(result.rewritten());
        assertEquals("点签电子合同 怎么使用？", result.query());
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
    void skipsShortFollowUpsAndRetainsTheLatestSubjectSwitch() {
        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "我要怎么签合同"),
            message("ai", "可以手写签名或使用电子签名。"),
            message("user", "企业的呢？"),
            message("ai", "企业可以完成认证后签署。"),
            message("user", "具体先做哪一步？")), "具体先做哪一步？");

        assertTrue(result.contextDependent());
        assertTrue(result.previousQuestionMerged());
        assertEquals("企业怎么签合同 具体先做哪一步？", result.query());
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
    void recognizesObjectLedStateContinuationFollowUp() {
        String previousQuestion = "合同已经发出去了，但目前还没有人签署，我漏传了附件。";
        String currentQuestion = "附件还能补进去吗？";

        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", previousQuestion),
            message("user", currentQuestion)), currentQuestion);

        assertTrue(result.contextDependent());
        assertTrue(result.rewritten());
        assertTrue(result.previousQuestionMerged());
        assertEquals(previousQuestion + " " + currentQuestion, result.query());
    }

    @Test
    void doesNotInventContextForStateContinuationWithoutPreviousUserTurn() {
        String question = "现在还能把号码改掉吗？";

        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(), question);

        assertTrue(result.contextDependent());
        assertFalse(result.rewritten());
        assertEquals(question, result.query());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "具体先做哪一步？",
        "下一步呢？",
        "接下来要做什么？",
        "具体怎么操作？",
        "需要准备什么材料？",
        "还要提供哪些资料？",
        "大概需要多久？",
        "办理要几天？",
        "费用是多少？",
        "最多能签几份？",
        "还需要重新发起吗？"
    })
    void mergesGeneralEllipticalFollowUpsWithThePreviousBusinessScenario(
            String currentQuestion) {
        String previousQuestion = "我们公司的法人刚刚变更，点签里还是旧法人。";

        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", previousQuestion),
            message("ai", "需要先更新企业信息。"),
            message("user", currentQuestion)), currentQuestion);

        assertTrue(result.contextDependent());
        assertTrue(result.rewritten());
        assertTrue(result.previousQuestionMerged());
        assertEquals(previousQuestion + " " + currentQuestion, result.query());
    }

    @Test
    void mergesAStateCorrectionWithoutLosingThePreviousBusinessScenario() {
        String previousQuestion = "我刚发起一份合同，发现正文写错了，对方还没有签。";
        String currentQuestion = "更正一下，不是正文，是接收人手机号错了。";

        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", previousQuestion),
            message("user", currentQuestion)), currentQuestion);

        assertTrue(result.contextDependent());
        assertTrue(result.previousQuestionMerged());
        assertEquals(previousQuestion + " " + currentQuestion, result.query());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "电子合同有法律效力吗？",
        "企业认证需要什么材料？",
        "法人变更后如何更新企业信息？",
        "如何申请电子印章？"
    })
    void preservesExplicitStandaloneQuestions(String currentQuestion) {
        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "专业版套餐快到期了。"),
            message("ai", "可以先确认套餐到期时间。"),
            message("user", currentQuestion)), currentQuestion);

        assertFalse(result.contextDependent());
        assertFalse(result.rewritten());
        assertEquals(currentQuestion, result.query());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "换个问题，合同到期后还能下载吗？",
        "另外，个人实名认证怎么做？",
        "顺便问一下，电子印章如何申请？"
    })
    void resetsContextForExplicitTopicSwitches(String currentQuestion) {
        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "我们公司的法人刚刚变更，点签里还是旧法人。"),
            message("ai", "需要先更新企业信息。"),
            message("user", currentQuestion)), currentQuestion);

        assertFalse(result.contextDependent());
        assertFalse(result.rewritten());
        assertEquals(currentQuestion, result.query());
    }

    @Test
    void consumesStructuredContractTypeClarification() {
        String question = "二手房买卖合同";
        BotMessage clarification = message("ai",
            "请问您要签署的是商品房买卖合同还是二手房买卖合同？");
        clarification.setMetadata("""
            {"pendingClarification":{"intentCode":"CONTRACT_TYPE_CAPABILITY",
            "missingSlot":"contractType","queryTemplate":"点签 是否支持签署 {contractType}",
            "expiresAfterTurns":1}}
            """);

        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "你们平台可以签房屋买卖合同吗？"),
            clarification,
            message("user", question)), question);

        assertTrue(result.contextDependent());
        assertTrue(result.rewritten());
        assertTrue(result.clarificationResolved());
        assertEquals("metadata", result.clarificationSource());
        assertEquals("点签 是否支持签署 二手房买卖合同", result.query());
    }

    @Test
    void recoversLegacyContractTypeClarificationWithoutMetadata() {
        String question = "二手房买卖合同";

        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "您们平台可以签房屋买卖合同吗？"),
            message("ai", "请问您要签署的是商品房买卖合同还是二手房买卖合同？"),
            message("user", question)), question);

        assertTrue(result.clarificationResolved());
        assertEquals("legacy", result.clarificationSource());
        assertEquals("点签 是否支持签署 二手房买卖合同", result.query());
    }

    @Test
    void doesNotTreatContractNameAsClarificationAfterAnUnrelatedQuestion() {
        String question = "劳动合同";

        ContextualQueryResolver.Resolution result = resolver.resolve(List.of(
            message("user", "合同怎么下载？"),
            message("ai", "请问您要处理哪种合同？"),
            message("user", question)), question);

        assertFalse(result.contextDependent());
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
