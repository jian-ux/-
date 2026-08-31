package com.feisheng.bot.core.service;

import com.feisheng.bot.core.entity.BotMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomerContextRecallServiceTest {
    @Test
    void recallsIndependentSourcesConcurrently() throws Exception {
        CustomerProfileService profile = mock(CustomerProfileService.class);
        CustomerLongTermMemoryService memory = mock(CustomerLongTermMemoryService.class);
        CustomerConversationHistoryService history = mock(CustomerConversationHistoryService.class);
        ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(3);
        CountDownLatch entered = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        AtomicReference<String> historyValue = new AtomicReference<>();
        when(profile.load("web", "u1")).thenAnswer(i -> {
            int n = running.incrementAndGet(); maxRunning.accumulateAndGet(n, Math::max);
            entered.countDown(); release.await(1, TimeUnit.SECONDS); running.decrementAndGet();
            return CustomerProfileService.ProfileSnapshot.empty();
        });
        when(memory.load("web", "u1")).thenAnswer(i -> {
            int n = running.incrementAndGet(); maxRunning.accumulateAndGet(n, Math::max);
            entered.countDown(); release.await(1, TimeUnit.SECONDS); running.decrementAndGet();
            return CustomerLongTermMemoryService.Snapshot.empty();
        });
        when(history.contextFor("web", "u1", 9L)).thenAnswer(i -> {
            int n = running.incrementAndGet(); maxRunning.accumulateAndGet(n, Math::max);
            entered.countDown(); release.await(1, TimeUnit.SECONDS); running.decrementAndGet();
            historyValue.set("历史片段"); return historyValue.get();
        });

        ExecutorService caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        var future = java.util.concurrent.CompletableFuture.supplyAsync(() ->
            new CustomerContextRecallService(profile, memory, history, executor, 1000)
                .recall("web", "u1", 9L, "这个合同", null, List.<BotMessage>of()),
            caller);
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        release.countDown();
        CustomerContextSnapshot snapshot = future.get(2, TimeUnit.SECONDS);
        assertEquals("历史片段", snapshot.historyContext());
        assertTrue(maxRunning.get() >= 2);
        caller.shutdownNow();
        executor.shutdownNow();
    }

    @Test
    void isolatesLoaderFailureAndQueueSaturation() throws Exception {
        CustomerProfileService profile = mock(CustomerProfileService.class);
        CustomerLongTermMemoryService memory = mock(CustomerLongTermMemoryService.class);
        CustomerConversationHistoryService history = mock(CustomerConversationHistoryService.class);
        when(profile.load(any(), any())).thenThrow(new IllegalStateException("db down"));
        when(memory.load(any(), any())).thenReturn(CustomerLongTermMemoryService.Snapshot.empty());
        when(history.contextFor(any(), any(), any())).thenReturn("");
        ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(3);
        CustomerContextSnapshot failed = new CustomerContextRecallService(
            profile, memory, history, executor, 100).recall("web", "u2", 1L, "合同", null, List.of());
        assertTrue(failed.profile().facts().isEmpty());
        assertEquals("failed", ((Map<?, ?>) failed.diagnostics().get("profile")).get("status"));
        executor.shutdownNow();

        ThreadPoolExecutorSaturation saturation = new ThreadPoolExecutorSaturation();
        CustomerContextSnapshot full = new CustomerContextRecallService(
            profile, memory, history, saturation.executor, 20)
            .recall("web", "u3", 2L, "合同", null, List.of());
        assertTrue(full.diagnostics().values().stream().anyMatch(value ->
            value.toString().contains("queue_full") || value.toString().contains("timeout")));
        saturation.close();
    }

    private static final class ThreadPoolExecutorSaturation implements AutoCloseable {
        final java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1),
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        ThreadPoolExecutorSaturation() {
            executor.execute(() -> { try { Thread.sleep(1000); } catch (InterruptedException ignored) { } });
            executor.execute(() -> { });
        }
        public void close() { executor.shutdownNow(); }
    }
}
