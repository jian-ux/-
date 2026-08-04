package com.feisheng.bot.core.service;

import com.feisheng.bot.core.entity.BotMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmotionServiceTest {
    private final EmotionService service = new EmotionService();

    @Test
    void recognizesStrongAngerAndEscalates() {
        EmotionService.EmotionResult result = service.analyze(
            "真的气死我了！太垃圾了！我要投诉", List.of(), null);

        assertEquals(EmotionService.EmotionLabel.ANGER, result.label());
        assertEquals(EmotionService.EmotionRisk.HIGH, result.risk());
        assertEquals("P0", result.priority());
        assertTrue(result.shouldHandoff());
    }

    @Test
    void escalatesAfterThreeConsecutiveNegativeTurns() {
        EmotionService.EmotionResult result = service.analyze("还是很着急", List.of(
            message(1L, "user", "我很担心会造成损失"),
            message(2L, "ai", "正在处理"),
            message(3L, "user", "到底还要多久"),
            message(4L, "user", "还是很着急")), 4L);

        assertEquals(3, result.negativeStreak());
        assertEquals(EmotionService.EmotionRisk.HIGH, result.risk());
        assertTrue(result.shouldHandoff());
    }

    @Test
    void doesNotMisreadNegatedEmotion() {
        EmotionService.EmotionResult result = service.analyze(
            "不用着急，我并不生气，慢慢处理就行", List.of(), null);

        assertEquals(EmotionService.EmotionLabel.NEUTRAL, result.label());
        assertFalse(result.shouldHandoff());
    }

    @Test
    void reportsImprovementAfterNegativeTurn() {
        EmotionService.EmotionResult result = service.analyze("谢谢，已经解决了", List.of(
            message(1L, "user", "我真的很失望"),
            message(2L, "user", "谢谢，已经解决了")), 2L);

        assertEquals(EmotionService.EmotionLabel.SATISFACTION, result.label());
        assertEquals(EmotionService.EmotionTrend.IMPROVING, result.trend());
    }

    @Test
    void adaptsDeterministicReplyWithoutDuplicatingApology() {
        EmotionService.EmotionResult anger = service.analyze("太生气了", List.of(), null);

        assertEquals("抱歉给您带来不好的体验。订单正在配送。",
            service.adaptDeterministicReply("订单正在配送。", anger));
        assertEquals("抱歉，正在处理。",
            service.adaptDeterministicReply("抱歉，正在处理。", anger));
    }

    private BotMessage message(Long id, String role, String content) {
        BotMessage message = new BotMessage();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
