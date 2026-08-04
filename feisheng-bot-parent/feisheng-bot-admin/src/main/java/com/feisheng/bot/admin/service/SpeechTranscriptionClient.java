package com.feisheng.bot.admin.service;

import java.nio.file.Path;

public interface SpeechTranscriptionClient {
    String transcribe(Path audioPath, String fileName, String contentType,
                      SpeechTranscriptionService.SpeechConfig config);
}
