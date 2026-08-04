package com.feisheng.bot.gateway.service;

import com.feisheng.bot.gateway.dto.DingTalkMediaRequest;

public interface DingTalkMediaProcessor {
    String normalize(DingTalkMediaRequest request);
}
