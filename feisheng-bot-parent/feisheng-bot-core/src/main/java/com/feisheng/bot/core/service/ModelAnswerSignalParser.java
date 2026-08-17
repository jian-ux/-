package com.feisheng.bot.core.service;

public final class ModelAnswerSignalParser {
    public static final String NO_ANSWER_SIGNAL = "__NO_ANSWER__";
    public static final String PARTIAL_ANSWER_SIGNAL = "__ANSWER_PARTIAL__";

    public ParsedAnswer parse(String value) {
        if (value == null || value.isBlank()) {
            return new ParsedAnswer(Decision.EMPTY, "", true, null);
        }

        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').stripLeading();
        int lineEnd = normalized.indexOf('\n');
        String firstLine = (lineEnd >= 0 ? normalized.substring(0, lineEnd) : normalized).strip();
        String body = lineEnd >= 0 ? normalized.substring(lineEnd + 1).strip() : "";

        if (NO_ANSWER_SIGNAL.equals(firstLine)) {
            boolean valid = body.isBlank();
            return new ParsedAnswer(Decision.NO_ANSWER, "", valid,
                valid ? null : "no_answer_with_body");
        }
        if (PARTIAL_ANSWER_SIGNAL.equals(firstLine)) {
            if (body.isBlank()) {
                return new ParsedAnswer(Decision.EMPTY, "", false,
                    "partial_answer_without_body");
            }
            boolean nestedSignal = containsSignal(body);
            return new ParsedAnswer(Decision.ANSWER_PARTIAL, sanitizeSignals(body),
                !nestedSignal, nestedSignal ? "decision_signal_in_body" : null);
        }

        boolean misplacedSignal = containsSignal(normalized);
        return new ParsedAnswer(Decision.ANSWER, sanitizeSignals(normalized),
            !misplacedSignal, misplacedSignal ? "decision_signal_not_on_first_line" : null);
    }

    private boolean containsSignal(String value) {
        return value.contains(NO_ANSWER_SIGNAL) || value.contains(PARTIAL_ANSWER_SIGNAL);
    }

    private String sanitizeSignals(String value) {
        return value.replace(NO_ANSWER_SIGNAL, "")
            .replace(PARTIAL_ANSWER_SIGNAL, "")
            .replaceAll("(?m)^[ \\t]+$", "")
            .replaceAll("\n{3,}", "\n\n")
            .strip();
    }

    public enum Decision {
        ANSWER,
        ANSWER_PARTIAL,
        NO_ANSWER,
        EMPTY
    }

    public record ParsedAnswer(
            Decision decision, String content, boolean protocolValid, String violation) {
        public boolean isAnswer() {
            return decision == Decision.ANSWER || decision == Decision.ANSWER_PARTIAL;
        }

        public boolean isPartial() {
            return decision == Decision.ANSWER_PARTIAL;
        }

        public boolean isNoAnswer() {
            return decision == Decision.NO_ANSWER;
        }
    }
}
