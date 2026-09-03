package com.feisheng.bot.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextModelCallPolicyTest {

    @Test
    void clampsTierTimeoutToTheRemainingTurnBudget() {
        ContextModelCallPolicy policy = new ContextModelCallPolicy(3_000, 8_000, 4_000, 15_000, 0);
        long deadline = System.nanoTime() + 5_000_000_000L;

        int remaining = policy.requestTimeoutMs(ContextModelCallPolicy.Tier.DEEP, deadline);

        assertTrue(remaining > 0);
        assertTrue(remaining <= 5_000);
        assertTrue(remaining < 8_000);
        assertEquals(0, policy.maxRetries());
    }

    @Test
    void disablesSameModelRetriesEvenWhenMisconfigured() {
        ContextModelCallPolicy policy = new ContextModelCallPolicy(
                3_000, 8_000, 4_000, 15_000, 3);

        assertEquals(0, policy.maxRetries());
    }
}
