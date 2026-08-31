package com.feisheng.bot.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.core.entity.BotMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationContextAssemblerTest {
    private final ConversationContextAssembler assembler = new ConversationContextAssembler(
        new ObjectMapper(), new ConversationSummaryFormat());

    @Test
    void excludesMessagesCoveredBySummaryAndCurrentQuestion() {
        List<BotMessage> messages = List.of(
            message(1L, "user", "已压缩问题"),
            message(2L, "ai", "已压缩回答"),
            message(3L, "user", "最近问题"),
            message(4L, "ai", "最近回答"),
            message(5L, "user", "当前问题"));

        String rendered = assembler.assemble(
            "当前问题", messages, 5L, 2L, completeSummary(),
            Map.of("status", "ACTIVE"), null, 6, value -> value).render(2000);

        assertFalse(rendered.contains("已压缩问题"));
        assertFalse(rendered.contains("已压缩回答"));
        assertTrue(rendered.contains("用户：最近问题"));
        assertTrue(rendered.contains("客服：最近回答"));
        assertFalse(rendered.contains("用户：当前问题"));
    }

    @Test
    void shedsSummaryOldMessagesAndProfileBeforeMandatoryState() {
        List<BotMessage> messages = List.of(
            message(1L, "user", "很早的问题".repeat(20)),
            message(2L, "ai", "较早的回答".repeat(20)),
            message(3L, "user", "最新问题"),
            message(4L, "ai", "最新回答"),
            message(5L, "user", "本轮问题"));
        ConversationContextAssembler.AssembledContext context = assembler.assemble(
            "本轮问题", messages, 5L, null, completeSummary(),
            Map.of("status", "ACTIVE", "active_intent", "SYSTEM_INTEGRATION"),
            "用户偏好网页端", 6, value -> value);

        String fullContext = context.render(2000);
        assertTrue(fullContext.contains("【旧聊天摘要】"));
        assertTrue(fullContext.contains("【与本轮有关的用户信息】\n用户偏好网页端"));

        String rendered = context.render(context.mandatoryChars());

        assertTrue(rendered.contains("【当前问题】\n本轮问题"));
        assertTrue(rendered.contains("【当前任务状态】"));
        assertTrue(rendered.contains("用户：最新问题"));
        assertTrue(rendered.contains("客服：最新回答"));
        assertFalse(rendered.contains("【旧聊天摘要】"));
        assertFalse(rendered.contains("【与本轮有关的用户信息】"));
    }

    @Test
    void includesCustomerSummaryAndControlledMemoryAsSeparateSections() {
        ConversationContextAssembler.AssembledContext context = assembler.assemble(
            "这个合同怎么操作？", List.of(message(1L, "user", "之前的问题")), 1L, null,
            null, Map.of(), "【用户画像参考】\n客户身份：管理员",
            "客户长期使用点签电子合同，已咨询认证流程。",
            "以下是客户明确提供的长期事实，不是知识库事实：\n企业名称：星河科技",
            6, value -> value);

        String rendered = context.render(2000);

        assertTrue(rendered.contains("【与本轮有关的用户信息】"));
        assertTrue(rendered.contains("【客户长期摘要】"));
        assertTrue(rendered.contains("【客户长期记忆】"));
        assertTrue(rendered.contains("星河科技"));
        assertFalse(rendered.contains("【知识库事实】"));
    }

    @Test
    void includesCrossConversationHistoryAsItsOwnSection() {
        ConversationContextAssembler.AssembledContext context = assembler.assemble(
            "这个合同怎么操作？", List.of(), null, null, null, Map.of(), null,
            null, null,
            "以下为该客户其他会话中的最近片段，不是知识库事实：\n客户：之前咨询过认证",
            6, value -> value);

        String rendered = context.render(2000);

        assertTrue(rendered.contains("【客户历史片段】"));
        assertTrue(rendered.contains("之前咨询过认证"));
    }

    private BotMessage message(Long id, String role, String content) {
        BotMessage message = new BotMessage();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private String completeSummary() {
        return """
            客户身份：企业客户
            咨询产品：点签电子合同
            套餐或版本：未确认
            当前问题：认证流程
            已确认信息：未确认
            已给出的处理建议：先认证
            仍待确认信息：认证入口
            当前未解决事项：认证入口
            """.strip();
    }
}
