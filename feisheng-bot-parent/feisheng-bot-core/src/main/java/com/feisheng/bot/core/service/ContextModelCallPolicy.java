package com.feisheng.bot.core.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Request-local budgets for context decision model calls. */
@Component
public class ContextModelCallPolicy {
    public enum Tier { FAST, DEEP, BACKUP }

    private final int fastTimeoutMs;
    private final int deepTimeoutMs;
    private final int backupTimeoutMs;
    private final int overallTimeoutMs;
    private final int maxRetries;

    public ContextModelCallPolicy(
            @Value("${customer-service.layered-context.fast-timeout-ms:3000}") int fastTimeoutMs,
            @Value("${customer-service.layered-context.deep-timeout-ms:8000}") int deepTimeoutMs,
            @Value("${customer-service.layered-context.backup-timeout-ms:4000}") int backupTimeoutMs,
            @Value("${customer-service.layered-context.overall-timeout-ms:15000}") int overallTimeoutMs,
            @Value("${customer-service.layered-context.max-retries:0}") int maxRetries) {
        this.fastTimeoutMs = positive(fastTimeoutMs, 3_000);
        this.deepTimeoutMs = positive(deepTimeoutMs, 8_000);
        this.backupTimeoutMs = positive(backupTimeoutMs, 4_000);
        this.overallTimeoutMs = positive(overallTimeoutMs, 15_000);
        this.maxRetries = 0;
    }

    public int timeoutMs(Tier tier) {
        return switch (tier == null ? Tier.FAST : tier) {
            case FAST -> fastTimeoutMs;
            case DEEP -> deepTimeoutMs;
            case BACKUP -> backupTimeoutMs;
        };
    }

    /** Returns the smaller tier/turn budget, or zero when the turn is expired. */
    public int requestTimeoutMs(Tier tier, long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return 0;
        }
        long remainingMs = Math.max(1L, (remainingNanos + 999_999L) / 1_000_000L);
        return (int) Math.min(timeoutMs(tier), Math.min(Integer.MAX_VALUE, remainingMs));
    }

    public long deadlineFromNow() {
        return System.nanoTime() + overallTimeoutMs * 1_000_000L;
    }

    public int overallTimeoutMs() {
        return overallTimeoutMs;
    }

    public int maxRetries() {
        return maxRetries;
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
