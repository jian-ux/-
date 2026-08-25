package com.feisheng.bot.gateway.service;

import com.feisheng.bot.gateway.dto.DingTalkMediaRequest;

public interface DingTalkMediaProcessor {
    String normalize(DingTalkMediaRequest request);

    /**
     * Normalizes media and optionally returns metadata for the persisted message.
     * The default keeps existing processors source-compatible.
     */
    default MediaResult process(DingTalkMediaRequest request) {
        return new MediaResult(normalize(request), "text", null);
    }

    record MediaResult(String content, String contentType, String metadata) {}
}
