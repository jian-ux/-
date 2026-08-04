package com.feisheng.bot.core.client;

import com.feisheng.bot.core.service.SpeechSynthesisService;

/** Client contract for text-to-speech providers. */
public interface SpeechSynthesisClient {
    AudioResponse synthesize(String text, SpeechSynthesisService.SynthesisConfig config);

    record AudioResponse(byte[] audio, String contentType) {}
}
