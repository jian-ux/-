package com.feisheng.bot.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.core.entity.BotMemoryOutboxEvent;
import com.feisheng.bot.core.mapper.BotMemoryOutboxEventMapper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Set;

/** Writes customer-memory events with a durable unique idempotency key. */
@Service
public class CustomerMemoryOutboxService {
    public static final String CONTEXT_SUMMARY = "CONTEXT_SUMMARY";
    public static final String PROFILE_AI_EXTRACTION = "PROFILE_AI_EXTRACTION";
    public static final String CUSTOMER_LONG_TERM_SUMMARY = "CUSTOMER_LONG_TERM_SUMMARY";
    public static final String MEDIA_OCR_MEMORY = "MEDIA_OCR_MEMORY";
    public static final Set<String> EVENT_TYPES = Set.of(
        CONTEXT_SUMMARY, PROFILE_AI_EXTRACTION,
        CUSTOMER_LONG_TERM_SUMMARY, MEDIA_OCR_MEMORY);

    private final BotMemoryOutboxEventMapper mapper;

    public CustomerMemoryOutboxService(BotMemoryOutboxEventMapper mapper) {
        this.mapper = mapper;
    }

    public BotMemoryOutboxEvent enqueue(String eventType, String dedupKey,
                                        Long customerId, Long conversationId,
                                        Long sourceMessageId, String payload) {
        String normalizedType = eventType == null ? "" : eventType.trim();
        String normalizedKey = dedupKey == null ? "" : dedupKey.trim();
        if (!EVENT_TYPES.contains(normalizedType) || normalizedKey.isBlank()) return null;
        BotMemoryOutboxEvent existing = mapper.selectOne(new LambdaQueryWrapper<BotMemoryOutboxEvent>()
            .eq(BotMemoryOutboxEvent::getDedupKey, normalizedKey).last("LIMIT 1"));
        if (existing != null) return existing;
        BotMemoryOutboxEvent event = new BotMemoryOutboxEvent();
        event.setEventType(normalizedType);
        event.setDedupKey(normalizedKey);
        event.setCustomerId(customerId);
        event.setConversationId(conversationId);
        event.setSourceMessageId(sourceMessageId);
        event.setPayload(payload);
        event.setStatus("PENDING");
        event.setAttempts(0);
        event.setAvailableAt(new Date());
        try {
            mapper.insert(event);
            return event;
        } catch (RuntimeException duplicateOrTransient) {
            // A concurrent request may have won the unique dedup key race.
            BotMemoryOutboxEvent raced = mapper.selectOne(new LambdaQueryWrapper<BotMemoryOutboxEvent>()
                .eq(BotMemoryOutboxEvent::getDedupKey, normalizedKey).last("LIMIT 1"));
            return raced == null ? null : raced;
        }
    }
}
