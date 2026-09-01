package com.feisheng.bot.core.service;

import com.feisheng.bot.core.entity.BotMessage;

import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects bounded customer context and deliberately makes no semantic decision. */
public class ContextCandidateSelector {
    private static final String PLAYGROUND = "playground";

    public List<ContextCandidate> select(String channelType, String channelUserId, Long conversationId,
                                         String originalQuery, ConversationStateService.Snapshot state,
                                         List<BotMessage> recent, CustomerContextSnapshot customerContext,
                                         int maxCandidates) {
        if (maxCandidates <= 0 || isBlank(channelType) || isBlank(channelUserId)
                || PLAYGROUND.equalsIgnoreCase(channelType.trim())) return List.of();
        Map<String, ContextCandidate> candidates = new LinkedHashMap<>();
        addActiveTask(candidates, channelType.trim(), channelUserId.trim(), conversationId, state);
        addRecent(candidates, channelType.trim(), channelUserId.trim(), conversationId, recent);
        addCustomerRecords(candidates, channelType.trim(), channelUserId.trim(), customerContext);
        return candidates.values().stream().limit(maxCandidates).toList();
    }

    private void addActiveTask(Map<String, ContextCandidate> candidates, String channelType, String channelUserId,
                               Long conversationId, ConversationStateService.Snapshot state) {
        if (state == null || state.status() == ConversationStateService.Status.IDLE
                || isBlank(state.standaloneQuery())) return;
        candidates.put("task:active", new ContextCandidate("task:active", "active_task",
                state.standaloneQuery().trim(), conversationId, null, channelType, channelUserId, 1D,
                null, null, "active_conversation_task"));
    }

    private void addRecent(Map<String, ContextCandidate> candidates, String channelType, String channelUserId,
                           Long conversationId, List<BotMessage> recent) {
        if (recent == null) return;
        recent.stream().filter(message -> message != null && !isBlank(message.getContent()) && message.getId() != null)
                .sorted(Comparator.comparing(BotMessage::getCreateTime, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(BotMessage::getId, Comparator.reverseOrder()))
                .forEach(message -> {
                    Long sessionId = message.getConversationId();
                    if (conversationId == null || sessionId == null || !conversationId.equals(sessionId)) return;
                    String id = "message:" + message.getId();
                    candidates.putIfAbsent(id, new ContextCandidate(id, "recent_message", message.getContent().trim(),
                            sessionId, message.getId(), channelType, channelUserId, 1D,
                            message.getCreateTime(), null, "recent_session"));
                });
    }

    private void addCustomerRecords(Map<String, ContextCandidate> candidates, String channelType, String channelUserId,
                                    CustomerContextSnapshot snapshot) {
        if (snapshot == null) return;
        Date now = new Date();
        for (CustomerContextSnapshot.ContextRecord record : snapshot.contextRecords()) {
            if (record == null || isBlank(record.contextId()) || isBlank(record.content())
                    || !channelType.equals(record.channelType()) || !channelUserId.equals(record.channelUserId())
                    || (record.expiresAt() != null && !record.expiresAt().after(now))) continue;
            candidates.putIfAbsent(record.contextId(), new ContextCandidate(record.contextId(), record.sourceType(),
                    record.content(), record.sessionId(), record.messageId(), record.channelType(),
                    record.channelUserId(), record.confidence(), record.createdAt(), record.expiresAt(), record.reason()));
        }
    }

    private boolean isBlank(String value) { return value == null || value.isBlank(); }
}
