package com.feisheng.bot.core.service.tool;

import com.feisheng.bot.core.entity.BotMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrderReferenceResolverTest {
    private final OrderReferenceResolver resolver = new OrderReferenceResolver();

    @Test
    void resolvesLabeledAndMixedOrderNumbers() {
        assertEquals("12345678", resolver.resolve("订单号：12345678，帮我查一下", List.of()));
        assertEquals("FS202607170001", resolver.resolve("查订单 FS202607170001", List.of()));
    }

    @Test
    void resolvesOrderNumberFromRecentUserHistory() {
        assertEquals("FS202607170001", resolver.resolve("物流到哪了", List.of(
            message("user", "订单号是 FS202607170001"),
            message("ai", "正在查询"))));
    }

    @Test
    void doesNotTreatOfficialPhoneAsBareOrderNumber() {
        assertNull(resolver.resolve("客服热线18689633999", List.of()));
    }

    private BotMessage message(String role, String content) {
        BotMessage message = new BotMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
