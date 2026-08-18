package com.feisheng.bot.core.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.feisheng.bot.core.entity.BotIntent;
import com.feisheng.bot.core.mapper.BotIntentMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentServiceTest {
    @Test
    void matchesLongestEnabledKeywordAndRendersTemplateVariables() {
        BotIntentMapper mapper = mock(BotIntentMapper.class);
        IntentService service = new IntentService(mapper);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            intent(1L, "退款咨询", "退款,退钱", "已识别{{intent}}：{{keyword}}", 1),
            intent(2L, "退款进度", "退款进度", "请提供订单号", 1)));

        IntentService.IntentMatch match = service.match("请问退款进度怎么查").orElseThrow();

        assertEquals(2L, match.intentId());
        assertEquals("退款进度", match.keyword());
        assertEquals("请提供订单号", match.reply());
    }

    @Test
    void ignoresDisabledAndIncompleteRules() {
        BotIntentMapper mapper = mock(BotIntentMapper.class);
        IntentService service = new IntentService(mapper);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            intent(1L, "退款咨询", "退款", "已禁用", 0),
            intent(2L, "退款咨询", "退款", " ", 1)));

        assertTrue(service.match("我要退款").isEmpty());
        assertTrue(service.match(" ").isEmpty());
    }

    @Test
    void matchesEnglishKeywordsCaseInsensitively() {
        BotIntentMapper mapper = mock(BotIntentMapper.class);
        IntentService service = new IntentService(mapper);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            intent(3L, "API咨询", "OpenAPI", "请查看接口文档", 1)));

        assertEquals("OpenAPI", service.match("openapi 怎么接入").orElseThrow().keyword());
    }

    @Test
    void doesNotLetShortBroadKeywordInterceptSubstantiveQuestion() {
        BotIntentMapper mapper = mock(BotIntentMapper.class);
        IntentService service = new IntentService(mapper);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            intent(4L, "合同咨询", "合同", "合同固定回复", 1)));

        assertTrue(service.match("合同怎么修改？").isEmpty());
    }

    @Test
    void keepsShortKeywordForExplicitCommand() {
        BotIntentMapper mapper = mock(BotIntentMapper.class);
        IntentService service = new IntentService(mapper);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            intent(5L, "退款咨询", "退款", "请提供订单号", 1)));

        assertFalse(service.match("我要申请退款").isEmpty());
    }

    @Test
    void doesNotTreatQuestionEndingWithBroadKeywordAsACommand() {
        BotIntentMapper mapper = mock(BotIntentMapper.class);
        IntentService service = new IntentService(mapper);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            intent(6L, "登录咨询", "登录", "固定登录回复", 1)));

        assertTrue(service.match("怎么登录").isEmpty());
    }

    private BotIntent intent(Long id, String name, String keywords, String reply, int status) {
        BotIntent intent = new BotIntent();
        intent.setId(id);
        intent.setIntentName(name);
        intent.setIntentKeywords(keywords);
        intent.setReplyTemplate(reply);
        intent.setStatus(status);
        return intent;
    }
}
