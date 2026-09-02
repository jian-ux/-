package com.feisheng.bot.core.service.impl;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps the customer's latest expression separate from context added to make it
 * searchable. Context may add a subject, but it must not replace an explicit
 * requirement in the current message.
 */
final class CurrentTurnRequest {
    private final String originalQuestion;
    private final String contextualIntent;

    private CurrentTurnRequest(String originalQuestion, String contextualIntent) {
        this.originalQuestion = requireText(originalQuestion, "originalQuestion");
        this.contextualIntent = normalizeText(contextualIntent);
    }

    static CurrentTurnRequest of(String originalQuestion, String contextualIntent) {
        return new CurrentTurnRequest(originalQuestion, contextualIntent);
    }

    String originalQuestion() {
        return originalQuestion;
    }

    String contextualIntent() {
        return contextualIntent;
    }

    String primaryRetrievalQuery(String candidateQuery) {
        String candidate = normalizeText(candidateQuery);
        if (candidate.isEmpty()) {
            return originalQuestion;
        }
        if (!hasContextualExpansion()) {
            return candidate;
        }
        String normalizedCandidate = normalizeForComparison(candidate);
        String normalizedOriginal = normalizeForComparison(originalQuestion);
        if (normalizedCandidate.contains(normalizedOriginal)) {
            return candidate;
        }
        if (normalizedOriginal.contains(normalizedCandidate)) {
            return originalQuestion;
        }
        return candidate.replaceFirst("[。！？?!]+$", "")
            + " 当前问题：" + originalQuestion;
    }

    String promptContext() {
        if (!hasContextualExpansion()) {
            return "当前轮请求：\n- 原始问题：" + originalQuestion;
        }
        return "当前轮请求：\n- 原始问题：" + originalQuestion
            + "\n- 上下文补全的业务意图：" + contextualIntent;
    }

    Map<String, String> diagnostics() {
        return Map.of(
            "originalQuestion", originalQuestion,
            "contextualIntent", contextualIntent);
    }

    private boolean hasContextualExpansion() {
        return !contextualIntent.isEmpty()
            && !normalizeForComparison(contextualIntent)
                .equals(normalizeForComparison(originalQuestion));
    }

    private static String requireText(String value, String name) {
        String normalized = normalizeText(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeForComparison(String value) {
        return Objects.requireNonNullElse(value, "").toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
