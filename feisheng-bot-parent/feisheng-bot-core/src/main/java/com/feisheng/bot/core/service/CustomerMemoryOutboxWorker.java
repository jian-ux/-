package com.feisheng.bot.core.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.feisheng.bot.core.entity.BotMemoryOutboxEvent;
import com.feisheng.bot.core.mapper.BotMemoryOutboxEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Claims and completes outbox rows without blocking the chat request. */
@Service
public class CustomerMemoryOutboxWorker {
    private final BotMemoryOutboxEventMapper mapper;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong retryCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final java.util.Map<String, Consumer<BotMemoryOutboxEvent>> handlers;
    @Autowired(required = false)
    @Qualifier("customerMemoryOutboxExecutor")
    private TaskExecutor executor;
    @Value("${customer-memory.outbox.enabled:true}")
    private boolean schedulingEnabled = true;
    @Value("${customer-memory.outbox.batch-size:20}")
    private int scheduledBatchSize = 20;

    @Autowired
    public CustomerMemoryOutboxWorker(BotMemoryOutboxEventMapper mapper) {
        this(mapper, 60, 5);
    }

    public CustomerMemoryOutboxWorker(BotMemoryOutboxEventMapper mapper,
                                      int leaseSeconds, int maxAttempts) {
        this(mapper, leaseSeconds, maxAttempts, java.util.Map.of());
    }

    public CustomerMemoryOutboxWorker(BotMemoryOutboxEventMapper mapper,
                                      int leaseSeconds, int maxAttempts,
                                      java.util.Map<String, Consumer<BotMemoryOutboxEvent>> handlers) {
        this.mapper = mapper;
        this.leaseSeconds = Math.max(1, leaseSeconds);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.handlers = handlers == null ? java.util.Map.of() : java.util.Map.copyOf(handlers);
    }

    public int processBatch(int limit) {
        List<BotMemoryOutboxEvent> rows = mapper.selectAvailable(Math.max(1, limit));
        int processed = 0;
        if (rows == null) return 0;
        for (BotMemoryOutboxEvent event : rows) {
            if (event == null || event.getId() == null
                    || mapper.claim(event.getId(), leaseSeconds) != 1) continue;
            try {
                processEvent(event);
                mapper.update(null, new LambdaUpdateWrapper<BotMemoryOutboxEvent>()
                    .eq(BotMemoryOutboxEvent::getId, event.getId())
                    .set(BotMemoryOutboxEvent::getStatus, "DONE")
                    .set(BotMemoryOutboxEvent::getProcessedAt, new Date())
                    .set(BotMemoryOutboxEvent::getLockedUntil, null));
                processed++;
                processedCount.incrementAndGet();
            } catch (RuntimeException failure) {
                retry(event, failure);
            }
        }
        return processed;
    }

    @Scheduled(fixedDelayString = "${customer-memory.outbox.poll-delay-ms:1000}")
    public void scheduledPoll() {
        if (!schedulingEnabled) return;
        Runnable task = () -> processBatch(scheduledBatchSize);
        if (executor != null) executor.execute(task); else task.run();
    }

    protected void processEvent(BotMemoryOutboxEvent event) {
        if (!CustomerMemoryOutboxService.EVENT_TYPES.contains(event.getEventType())) {
            throw new IllegalArgumentException("unknown_event_type");
        }
        if (event.getPayload() == null || event.getPayload().isBlank()) {
            throw new IllegalArgumentException("empty_payload");
        }
        Consumer<BotMemoryOutboxEvent> handler = handlers.get(event.getEventType());
        if (handler != null) handler.accept(event);
        // Handlers are deliberately idempotent: the dedup key is the source of truth.
    }

    private void retry(BotMemoryOutboxEvent event, RuntimeException failure) {
        int attempts = event.getAttempts() == null ? 0 : event.getAttempts();
        int nextAttempts = attempts + 1;
        if (nextAttempts >= maxAttempts) {
            failedCount.incrementAndGet();
            mapper.update(null, new LambdaUpdateWrapper<BotMemoryOutboxEvent>()
                .eq(BotMemoryOutboxEvent::getId, event.getId())
                .set(BotMemoryOutboxEvent::getStatus, "FAILED")
                .set(BotMemoryOutboxEvent::getAttempts, nextAttempts)
                .set(BotMemoryOutboxEvent::getLastErrorCode, failure.getClass().getSimpleName())
                .set(BotMemoryOutboxEvent::getLastErrorMessage, safeMessage(failure))
                .set(BotMemoryOutboxEvent::getLockedUntil, null));
            return;
        }
        retryCount.incrementAndGet();
        long delaySeconds = Math.min(300L, 1L << Math.min(8, nextAttempts));
        mapper.update(null, new LambdaUpdateWrapper<BotMemoryOutboxEvent>()
            .eq(BotMemoryOutboxEvent::getId, event.getId())
            .set(BotMemoryOutboxEvent::getStatus, "PENDING")
            .set(BotMemoryOutboxEvent::getAttempts, nextAttempts)
            .set(BotMemoryOutboxEvent::getAvailableAt,
                Date.from(Instant.now().plus(Duration.ofSeconds(delaySeconds))))
            .set(BotMemoryOutboxEvent::getLastErrorCode, failure.getClass().getSimpleName())
            .set(BotMemoryOutboxEvent::getLastErrorMessage, safeMessage(failure))
            .set(BotMemoryOutboxEvent::getLockedUntil, null));
    }

    private String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return "outbox_processing_failed";
        String redacted = message
            .replaceAll("(?i)(api[-_ ]?key|token|secret|password)\\s*[=:]\\s*\\S+", "$1=[REDACTED]")
            .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]");
        return redacted.substring(0, Math.min(500, redacted.length()));
    }

    public long processedCount() { return processedCount.get(); }
    public long retryCount() { return retryCount.get(); }
    public long failedCount() { return failedCount.get(); }
}
