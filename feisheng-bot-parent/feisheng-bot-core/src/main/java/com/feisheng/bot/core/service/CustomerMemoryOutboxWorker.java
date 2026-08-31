package com.feisheng.bot.core.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.feisheng.bot.core.entity.BotMemoryOutboxEvent;
import com.feisheng.bot.core.entity.BotConversation;
import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.mapper.BotMemoryOutboxEventMapper;
import com.feisheng.bot.core.mapper.BotConversationMapper;
import com.feisheng.bot.core.mapper.BotMessageMapper;
import com.feisheng.bot.core.service.impl.AiModelServiceImpl;
import com.feisheng.bot.core.dto.ChatResponse;
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
    private static final String SUMMARY_SYSTEM_PROMPT =
        "你是客服对话摘要器，只输出按字段排列的对话摘要，不要回答客户问题。";
    private final BotMemoryOutboxEventMapper mapper;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong retryCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final java.util.Map<String, Consumer<BotMemoryOutboxEvent>> handlers;
    private final BotConversationMapper conversationMapper;
    private final BotMessageMapper messageMapper;
    private final CustomerProfileService profileService;
    private final CustomerLongTermMemoryService longTermMemoryService;
    private final CustomerMediaMemoryService mediaMemoryService;
    private final AiModelServiceImpl aiModelService;
    private final ConversationSummaryFormat summaryFormat;
    @Autowired(required = false)
    @Qualifier("customerMemoryOutboxExecutor")
    private TaskExecutor executor;
    @Value("${customer-memory.outbox.enabled:true}")
    private boolean schedulingEnabled = true;
    @Value("${customer-memory.outbox.batch-size:20}")
    private int scheduledBatchSize = 20;

    public CustomerMemoryOutboxWorker(BotMemoryOutboxEventMapper mapper) {
        this(mapper, 60, 5, java.util.Map.of(), null, null, null, null, null, null, null);
    }

    public CustomerMemoryOutboxWorker(BotMemoryOutboxEventMapper mapper,
                                      int leaseSeconds, int maxAttempts) {
        this(mapper, leaseSeconds, maxAttempts, java.util.Map.of());
    }

    public CustomerMemoryOutboxWorker(BotMemoryOutboxEventMapper mapper,
                                      int leaseSeconds, int maxAttempts,
                                      java.util.Map<String, Consumer<BotMemoryOutboxEvent>> handlers) {
        this(mapper, leaseSeconds, maxAttempts, handlers, null, null, null, null, null, null, null);
    }

    @Autowired
    public CustomerMemoryOutboxWorker(BotMemoryOutboxEventMapper mapper,
                                      BotConversationMapper conversationMapper,
                                      BotMessageMapper messageMapper,
                                      CustomerProfileService profileService,
                                      CustomerLongTermMemoryService longTermMemoryService,
                                      CustomerMediaMemoryService mediaMemoryService,
                                      AiModelServiceImpl aiModelService,
                                      ConversationSummaryFormat summaryFormat) {
        this(mapper, 60, 5, java.util.Map.of(), conversationMapper, messageMapper,
            profileService, longTermMemoryService, mediaMemoryService, aiModelService, summaryFormat);
    }

    private CustomerMemoryOutboxWorker(BotMemoryOutboxEventMapper mapper,
                                      int leaseSeconds, int maxAttempts,
                                      java.util.Map<String, Consumer<BotMemoryOutboxEvent>> handlers,
                                      BotConversationMapper conversationMapper,
                                      BotMessageMapper messageMapper,
                                      CustomerProfileService profileService,
                                      CustomerLongTermMemoryService longTermMemoryService,
                                      CustomerMediaMemoryService mediaMemoryService,
                                      AiModelServiceImpl aiModelService,
                                      ConversationSummaryFormat summaryFormat) {
        this.mapper = mapper;
        this.leaseSeconds = Math.max(1, leaseSeconds);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.handlers = handlers == null ? java.util.Map.of() : java.util.Map.copyOf(handlers);
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.profileService = profileService;
        this.longTermMemoryService = longTermMemoryService;
        this.mediaMemoryService = mediaMemoryService;
        this.aiModelService = aiModelService;
        this.summaryFormat = summaryFormat;
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
                mapper.update(null, new UpdateWrapper<BotMemoryOutboxEvent>()
                    .eq("id", event.getId())
                    .set("status", "DONE")
                    .set("processed_at", new Date())
                    .set("locked_until", null));
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
        if (handler != null) { handler.accept(event); return; }
        BotConversation conversation = event.getConversationId() == null || conversationMapper == null
            ? null : conversationMapper.selectById(event.getConversationId());
        if (conversation == null) return;
        switch (event.getEventType()) {
            case CustomerMemoryOutboxService.CONTEXT_SUMMARY -> processContextSummary(event, conversation);
            case CustomerMemoryOutboxService.PROFILE_AI_EXTRACTION -> {
                if (profileService != null) profileService.updateAndLoad(
                    conversation.getChannelType(), conversation.getChannelUserId(), event.getPayload());
            }
            case CustomerMemoryOutboxService.CUSTOMER_LONG_TERM_SUMMARY -> {
                if (longTermMemoryService != null) longTermMemoryService.updateFromCustomerMessage(
                    conversation.getChannelType(), conversation.getChannelUserId(), event.getPayload(),
                    event.getSourceMessageId());
            }
            case CustomerMemoryOutboxService.MEDIA_OCR_MEMORY -> {
                if (mediaMemoryService != null && messageMapper != null && event.getSourceMessageId() != null) {
                    BotMessage message = messageMapper.selectById(event.getSourceMessageId());
                    if (message != null) mediaMemoryService.saveFromMessage(
                        conversation.getChannelType(), conversation.getChannelUserId(), message);
                }
            }
            default -> throw new IllegalArgumentException("unknown_event_type");
        }
    }

    private void processContextSummary(BotMemoryOutboxEvent event, BotConversation conversation) {
        if (aiModelService == null || summaryFormat == null) return;
        ChatResponse response = aiModelService.chat(event.getPayload(), SUMMARY_SYSTEM_PROMPT);
        if (response == null || !response.isSuccess() || response.getContent() == null) {
            throw new IllegalStateException("summary_model_unavailable");
        }
        String summary = summaryFormat.normalizeModelOutput(response.getContent(), 2000).orElseThrow(
            () -> new IllegalArgumentException("invalid_summary_format"));
        conversation.setContextSummary(summary);
        conversation.setSummaryMessageId(event.getSourceMessageId());
        conversation.setSummaryUpdatedAt(new Date());
        conversationMapper.updateById(conversation);
    }

    private void retry(BotMemoryOutboxEvent event, RuntimeException failure) {
        int attempts = event.getAttempts() == null ? 0 : event.getAttempts();
        int nextAttempts = attempts + 1;
        if (nextAttempts >= maxAttempts) {
            failedCount.incrementAndGet();
            mapper.update(null, new UpdateWrapper<BotMemoryOutboxEvent>()
                .eq("id", event.getId())
                .set("status", "FAILED")
                .set("attempts", nextAttempts)
                .set("last_error_code", failure.getClass().getSimpleName())
                .set("last_error_message", safeMessage(failure))
                .set("locked_until", null));
            return;
        }
        retryCount.incrementAndGet();
        long delaySeconds = Math.min(300L, 1L << Math.min(8, nextAttempts));
        mapper.update(null, new UpdateWrapper<BotMemoryOutboxEvent>()
            .eq("id", event.getId())
            .set("status", "PENDING")
            .set("attempts", nextAttempts)
            .set("available_at",
                Date.from(Instant.now().plus(Duration.ofSeconds(delaySeconds))))
            .set("last_error_code", failure.getClass().getSimpleName())
            .set("last_error_message", safeMessage(failure))
            .set("locked_until", null));
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
