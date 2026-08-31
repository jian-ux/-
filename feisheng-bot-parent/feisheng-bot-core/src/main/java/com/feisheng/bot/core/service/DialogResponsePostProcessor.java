package com.feisheng.bot.core.service;

import java.util.Map;

/** Final response boundary for protocol parsing and shared metadata application. */
public final class DialogResponsePostProcessor {
    private final ModelAnswerSignalParser signalParser;

    public DialogResponsePostProcessor(ModelAnswerSignalParser signalParser) {
        this.signalParser = signalParser == null ? new ModelAnswerSignalParser() : signalParser;
    }

    public ModelAnswerSignalParser.ParsedAnswer parseModelOutput(String modelOutput) {
        return signalParser.parse(modelOutput);
    }

    public void applyMetadata(Map<String, Object> response, DialogResponseMetadata metadata) {
        if (metadata != null) metadata.applyTo(response);
    }
}
