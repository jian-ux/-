package com.feisheng.bot.core.service;

import java.util.List;

/** Immutable envelope for one dialog turn. */
public record TurnContext(String turnId, String channelType, String channelUserId, Long conversationId,
                          Long messageId, String originalQuery, List<ContextCandidate> candidates) {
    public TurnContext {
        turnId = turnId == null ? "" : turnId;
        channelType = channelType == null ? "" : channelType;
        channelUserId = channelUserId == null ? "" : channelUserId;
        originalQuery = originalQuery == null ? "" : originalQuery;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static TurnContext start(String turnId, String channelType, String channelUserId,
                                    Long conversationId, Long messageId, String originalQuery,
                                    List<ContextCandidate> candidates) {
        return new TurnContext(turnId, channelType, channelUserId, conversationId, messageId, originalQuery, candidates);
    }
}
