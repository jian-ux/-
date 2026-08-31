package com.feisheng.bot.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Centralized, runtime-tunable limits for the dialog pipeline. */
@Component
@ConfigurationProperties(prefix = "dialog.performance")
public class DialogPerformanceProperties {
    private int contextCoreThreads = 3;
    private int contextMaxThreads = 6;
    private int contextQueueCapacity = 32;
    private int recallDeadlineMs = 250;
    private int outboxBatchSize = 20;
    private int retrievalCacheTtlSeconds = 15;
    private int modelConnectTimeoutMs = 10000;
    private int modelReadTimeoutMs = 60000;
    private int circuitBreakerSlidingWindowSize = 10;
    private int circuitBreakerMinimumNumberOfCalls = 5;
    private int circuitBreakerFailureRateThreshold = 50;
    private int circuitBreakerWaitDurationSeconds = 30;

    private int positive(String name, int value) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    public int getContextCoreThreads() { return contextCoreThreads; }
    public void setContextCoreThreads(int value) { contextCoreThreads = positive("contextCoreThreads", value); }
    public int getContextMaxThreads() { return contextMaxThreads; }
    public void setContextMaxThreads(int value) { contextMaxThreads = positive("contextMaxThreads", value); }
    public int getContextQueueCapacity() { return contextQueueCapacity; }
    public void setContextQueueCapacity(int value) { contextQueueCapacity = positive("contextQueueCapacity", value); }
    public int getRecallDeadlineMs() { return recallDeadlineMs; }
    public void setRecallDeadlineMs(int value) { recallDeadlineMs = positive("recallDeadlineMs", value); }
    public int getOutboxBatchSize() { return outboxBatchSize; }
    public void setOutboxBatchSize(int value) { outboxBatchSize = positive("outboxBatchSize", value); }
    public int getRetrievalCacheTtlSeconds() { return retrievalCacheTtlSeconds; }
    public void setRetrievalCacheTtlSeconds(int value) { retrievalCacheTtlSeconds = positive("retrievalCacheTtlSeconds", value); }
    public int getModelConnectTimeoutMs() { return modelConnectTimeoutMs; }
    public void setModelConnectTimeoutMs(int value) { modelConnectTimeoutMs = positive("modelConnectTimeoutMs", value); }
    public int getModelReadTimeoutMs() { return modelReadTimeoutMs; }
    public void setModelReadTimeoutMs(int value) { modelReadTimeoutMs = positive("modelReadTimeoutMs", value); }
    public int getCircuitBreakerSlidingWindowSize() { return circuitBreakerSlidingWindowSize; }
    public void setCircuitBreakerSlidingWindowSize(int value) { circuitBreakerSlidingWindowSize = positive("circuitBreakerSlidingWindowSize", value); }
    public int getCircuitBreakerMinimumNumberOfCalls() { return circuitBreakerMinimumNumberOfCalls; }
    public void setCircuitBreakerMinimumNumberOfCalls(int value) { circuitBreakerMinimumNumberOfCalls = positive("circuitBreakerMinimumNumberOfCalls", value); }
    public int getCircuitBreakerFailureRateThreshold() { return circuitBreakerFailureRateThreshold; }
    public void setCircuitBreakerFailureRateThreshold(int value) { circuitBreakerFailureRateThreshold = positive("circuitBreakerFailureRateThreshold", value); }
    public int getCircuitBreakerWaitDurationSeconds() { return circuitBreakerWaitDurationSeconds; }
    public void setCircuitBreakerWaitDurationSeconds(int value) { circuitBreakerWaitDurationSeconds = positive("circuitBreakerWaitDurationSeconds", value); }
}
