package com.feisheng.bot.core.service;

import com.feisheng.bot.core.entity.BotMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Performs independent customer-context reads concurrently. A slow or failed
 * source is isolated and contributes an empty section instead of delaying the
 * whole dialog indefinitely.
 */
@Service
public class CustomerContextRecallService {
    private final CustomerProfileService profileService;
    private final CustomerLongTermMemoryService longTermMemoryService;
    private final CustomerConversationHistoryService historyService;
    private final Executor executor;
    private final long deadlineMs;

    public CustomerContextRecallService(
            CustomerProfileService profileService,
            CustomerLongTermMemoryService longTermMemoryService,
            CustomerConversationHistoryService historyService,
            @Qualifier("customerContextExecutor") Executor executor,
            @Value("${rag.context.recall-deadline-ms:250}") long deadlineMs) {
        this.profileService = profileService;
        this.longTermMemoryService = longTermMemoryService;
        this.historyService = historyService;
        this.executor = executor;
        this.deadlineMs = Math.max(1L, deadlineMs);
    }

    public CustomerContextSnapshot recall(
            String channelType,
            String channelUserId,
            Long conversationId,
            String question,
            ConversationStateService.Snapshot state,
            List<BotMessage> recent) {
        long started = System.nanoTime();
        Map<String, SourceResult<?>> futures = new LinkedHashMap<>();
        futures.put("profile", submit("profile", () -> profileService == null
                ? CustomerProfileService.ProfileSnapshot.empty()
                : profileService.load(channelType, channelUserId)));
        futures.put("longTermMemory", submit("longTermMemory", () -> longTermMemoryService == null
                ? CustomerLongTermMemoryService.Snapshot.empty()
                : longTermMemoryService.load(channelType, channelUserId)));
        futures.put("history", submit("history", () -> historyService == null
                ? "" : historyService.contextFor(channelType, channelUserId, conversationId)));

        CustomerProfileService.ProfileSnapshot profile = CustomerProfileService.ProfileSnapshot.empty();
        CustomerLongTermMemoryService.Snapshot longTerm = CustomerLongTermMemoryService.Snapshot.empty();
        String history = "";
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs);
        for (Map.Entry<String, SourceResult<?>> entry : futures.entrySet()) {
            SourceResult<?> source = entry.getValue();
            Object value = source.value(deadline);
            diagnostics.put(entry.getKey(), source.diagnostic());
            if ("profile".equals(entry.getKey()) && value instanceof CustomerProfileService.ProfileSnapshot v) {
                profile = v;
            } else if ("longTermMemory".equals(entry.getKey())
                    && value instanceof CustomerLongTermMemoryService.Snapshot v) {
                longTerm = v;
            } else if ("history".equals(entry.getKey()) && value instanceof String v) {
                history = v;
            }
        }
        diagnostics.put("totalMs", Math.max(0L,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        return new CustomerContextSnapshot(profile, longTerm, history, diagnostics,
                contextRecords(channelType, channelUserId, profile, longTerm, history));
    }

    private List<CustomerContextSnapshot.ContextRecord> contextRecords(
            String channelType, String channelUserId, CustomerProfileService.ProfileSnapshot profile,
            CustomerLongTermMemoryService.Snapshot longTerm, String history) {
        if (channelType == null || channelUserId == null || channelType.isBlank() || channelUserId.isBlank()
                || "playground".equalsIgnoreCase(channelType.trim())) return List.of();
        List<CustomerContextSnapshot.ContextRecord> records = new java.util.ArrayList<>();
        profile.facts().forEach((category, values) -> values.forEach((key, value) -> {
            if (value != null && !value.toString().isBlank()) records.add(new CustomerContextSnapshot.ContextRecord(
                    "profile:" + category + ":" + key, "customer_profile", value.toString(), null, null,
                    channelType.trim(), channelUserId.trim(), 1D, null, null, null, "customer_profile"));
        }));
        longTerm.memories().forEach((key, fact) -> {
            if (fact != null && fact.value() != null && !fact.value().isBlank()) records.add(
                    new CustomerContextSnapshot.ContextRecord("memory:" + key, "memory_fact", fact.value(),
                            null, null, channelType.trim(), channelUserId.trim(), fact.confidence(), null,
                            null, null, fact.source() == null ? "long_term_memory" : fact.source()));
        });
        if (history != null && !history.isBlank()) records.add(new CustomerContextSnapshot.ContextRecord(
                "history:cross_session", "conversation_history", history, null, null,
                channelType.trim(), channelUserId.trim(), 0.5D, null, null, null, "cross_session_history"));
        return List.copyOf(records);
    }

    private SourceResult<?> submit(String source, java.util.function.Supplier<?> loader) {
        try {
            return SourceResult.pending(CompletableFuture.supplyAsync(loader, executor));
        } catch (RejectedExecutionException e) {
            return SourceResult.failed("queue_full");
        } catch (RuntimeException e) {
            return SourceResult.failed("submit_failed");
        }
    }

    private static final class SourceResult<T> {
        private final CompletableFuture<T> future;
        private final String immediateStatus;
        private long startedNanos = System.nanoTime();
        private String status = "pending";
        private String error;

        private SourceResult(CompletableFuture<T> future, String immediateStatus) {
            this.future = future;
            this.immediateStatus = immediateStatus;
            if (immediateStatus != null) {
                status = immediateStatus;
                error = immediateStatus;
            }
        }

        static <T> SourceResult<T> pending(CompletableFuture<T> future) {
            return new SourceResult<>(future, null);
        }

        static <T> SourceResult<T> failed(String status) {
            return new SourceResult<>(null, status);
        }

        T value(long deadlineNanos) {
            if (future == null) return null;
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                status = "timeout";
                error = "deadline_exceeded";
                future.cancel(true);
                return null;
            }
            try {
                T value = future.get(remaining, TimeUnit.NANOSECONDS);
                status = "ok";
                return value;
            } catch (TimeoutException e) {
                status = "timeout";
                error = "deadline_exceeded";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                status = "interrupted";
                error = "interrupted";
            } catch (ExecutionException | CompletionException e) {
                status = "failed";
                error = e.getCause() == null ? e.getClass().getSimpleName()
                        : e.getCause().getClass().getSimpleName();
            }
            future.cancel(true);
            return null;
        }

        Map<String, Object> diagnostic() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status);
            result.put("latencyMs", Math.max(0L,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)));
            if (error != null) result.put("error", error);
            return result;
        }
    }
}
