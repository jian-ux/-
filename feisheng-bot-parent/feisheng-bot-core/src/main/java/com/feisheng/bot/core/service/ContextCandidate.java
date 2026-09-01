package com.feisheng.bot.core.service;

import java.util.Date;

/** A bounded, auditable context item. Selection does not imply semantic use. */
public record ContextCandidate(
        String contextId, String sourceType, String content, Long sessionId, Long messageId,
        String channelType, String channelUserId, Double confidence, Date createdAt, Date expiresAt,
        String reason) {
    public ContextCandidate {
        contextId = contextId == null ? "" : contextId;
        sourceType = sourceType == null ? "" : sourceType;
        content = content == null ? "" : content;
        channelType = channelType == null ? "" : channelType;
        channelUserId = channelUserId == null ? "" : channelUserId;
        confidence = confidence == null ? 0D : Math.max(0D, Math.min(1D, confidence));
        createdAt = createdAt == null ? null : new Date(createdAt.getTime());
        expiresAt = expiresAt == null ? null : new Date(expiresAt.getTime());
        reason = reason == null ? "" : reason;
    }

    @Override
    public Date createdAt() {
        return createdAt == null ? null : new Date(createdAt.getTime());
    }

    @Override
    public Date expiresAt() {
        return expiresAt == null ? null : new Date(expiresAt.getTime());
    }
}
