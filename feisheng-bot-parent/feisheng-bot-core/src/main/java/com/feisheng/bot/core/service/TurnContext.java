package com.feisheng.bot.core.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable envelope for one dialog turn. */
public record TurnContext(String turnId, String channelType, String channelUserId, Long conversationId,
                          Long messageId, String originalQuery, List<ContextCandidate> candidates) {
    public TurnContext {
        turnId = turnId == null ? "" : turnId;
        channelType = channelType == null ? "" : channelType;
        channelUserId = channelUserId == null ? "" : channelUserId;
        originalQuery = originalQuery == null ? "" : originalQuery;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (candidates.size() > 12) {
            throw new IllegalArgumentException("candidate_budget_exceeded");
        }
        Set<String> candidateIds = new LinkedHashSet<>();
        for (ContextCandidate candidate : candidates) {
            if (candidate == null || candidate.contextId().isBlank()
                    || !candidateIds.add(candidate.contextId())) {
                throw new IllegalArgumentException("invalid_candidate_envelope");
            }
            if (!channelType.equals(candidate.channelType())
                    || !channelUserId.equals(candidate.channelUserId())) {
                throw new IllegalArgumentException("cross_customer_candidate");
            }
        }
    }

    public static TurnContext start(String turnId, String channelType, String channelUserId,
                                    Long conversationId, Long messageId, String originalQuery,
                                    List<ContextCandidate> candidates) {
        return new TurnContext(turnId, channelType, channelUserId, conversationId, messageId, originalQuery, candidates);
    }
}
