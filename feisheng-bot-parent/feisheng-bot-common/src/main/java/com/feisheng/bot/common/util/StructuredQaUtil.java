package com.feisheng.bot.common.util;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Stable identifiers and comparisons for reviewed structured question-answer units. */
public final class StructuredQaUtil {
    private static final Pattern QUESTION_SEPARATORS = Pattern.compile("[\\p{P}\\p{S}\\s]+");
    private static final Pattern ANSWER_WHITESPACE = Pattern.compile("\\s+");
    private static final List<String> QUESTION_FILLER_PREFIXES = List.of(
        "请问一下", "麻烦问下", "想问一下", "我想问", "请问");

    private StructuredQaUtil() {}

    public static String normalizeQuestion(String value) {
        String normalized = QUESTION_SEPARATORS.matcher(safe(value).toLowerCase(Locale.ROOT))
            .replaceAll("");
        normalized = normalized.replace("具备法律效力", "具有法律效力");
        boolean changed;
        do {
            changed = false;
            for (String prefix : QUESTION_FILLER_PREFIXES) {
                if (normalized.startsWith(prefix) && normalized.length() > prefix.length()) {
                    normalized = normalized.substring(prefix.length());
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return normalized;
    }

    public static String canonicalKey(String question) {
        String normalized = normalizeQuestion(question);
        return normalized.isBlank() ? "" : EmbeddingMetadataUtil.contentHash(normalized);
    }

    public static String normalizeAnswer(String value) {
        return ANSWER_WHITESPACE.matcher(safe(value).trim()).replaceAll(" ");
    }

    public static String answerFingerprint(String answer) {
        String normalized = normalizeAnswer(answer);
        return normalized.isBlank() ? "" : EmbeddingMetadataUtil.contentHash(normalized);
    }

    public static String sourceGroupKey(String question, String answer) {
        String normalizedQuestion = normalizeQuestion(question);
        String normalizedAnswer = normalizeAnswer(answer);
        if (normalizedQuestion.isBlank() || normalizedAnswer.isBlank()) return "";
        return EmbeddingMetadataUtil.contentHash(normalizedQuestion + "|" + normalizedAnswer);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
