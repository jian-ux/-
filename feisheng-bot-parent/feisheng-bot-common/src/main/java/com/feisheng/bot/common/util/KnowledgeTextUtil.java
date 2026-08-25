package com.feisheng.bot.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class KnowledgeTextUtil {
    private static final int FAQ_EMBEDDING_MAX_CHARS = 2000;
    private static final int CHUNK_EMBEDDING_MAX_CHARS = 4000;
    private static final Pattern SENTENCE_BOUNDARY_RE = Pattern.compile("(?<=[。！？!?；;])|\\n+");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private KnowledgeTextUtil() {}

    public static String faqEmbeddingText(String question, String keywords, String answer) {
        return faqEmbeddingText(question, keywords, answer, null);
    }

    public static String faqEmbeddingText(String question, String keywords, String answer,
                                          String alternateQuestions) {
        return faqEmbeddingParts(question, keywords, answer, alternateQuestions).get(0).embeddingText();
    }

    public static List<FaqEmbeddingPart> faqEmbeddingParts(
            String question, String keywords, String answer) {
        return faqEmbeddingParts(question, keywords, answer, null);
    }

    public static List<FaqEmbeddingPart> faqEmbeddingParts(
            String question, String keywords, String answer, String alternateQuestions) {
        StringBuilder prefixBuilder = new StringBuilder();
        append(prefixBuilder, question);
        append(prefixBuilder, keywords);
        for (String alias : questionAliases(alternateQuestions, question)) {
            append(prefixBuilder, alias);
        }
        String prefix = prefixBuilder.toString();
        String cleanAnswer = answer == null ? "" : answer.trim();
        String combined = join(prefix, cleanAnswer);
        if (combined.length() <= FAQ_EMBEDDING_MAX_CHARS) {
            return List.of(new FaqEmbeddingPart(0, cleanAnswer, combined));
        }

        int answerBudget = Math.max(1, FAQ_EMBEDDING_MAX_CHARS - prefix.length() - 1);
        List<String> answerParts = pack(cleanAnswer, answerBudget);
        List<FaqEmbeddingPart> parts = new ArrayList<>(answerParts.size());
        for (int i = 0; i < answerParts.size(); i++) {
            String answerPart = answerParts.get(i);
            parts.add(new FaqEmbeddingPart(i, answerPart,
                truncate(join(prefix, answerPart), FAQ_EMBEDDING_MAX_CHARS)));
        }
        return List.copyOf(parts);
    }

    /** Returns valid, distinct aliases without repeating the canonical question. */
    public static List<String> questionAliases(String json, String canonicalQuestion) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        Set<String> values = new LinkedHashSet<>();
        try {
            List<String> parsed = OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {});
            if (parsed != null) values.addAll(parsed);
        } catch (Exception ignored) {
            // Keep old/manual data searchable if it was saved as a delimited string.
            Collections.addAll(values, json.split("[,，;；\\r\\n]+"));
        }
        String canonical = canonicalQuestion(canonicalQuestion);
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .filter(value -> !canonical.equals(canonicalQuestion(value)))
            .toList();
    }

    public static String questionAliasesJson(String json, String canonicalQuestion) {
        try {
            return OBJECT_MAPPER.writeValueAsString(questionAliases(json, canonicalQuestion));
        } catch (Exception ignored) {
            return "[]";
        }
    }

    public static String chunkEmbeddingText(String sectionPath, String content) {
        StringBuilder text = new StringBuilder();
        append(text, sectionPath);
        append(text, content);
        return truncate(text.toString(), CHUNK_EMBEDDING_MAX_CHARS);
    }

    public static String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0) return "";
        if (value.length() <= maxLength) return value;
        int end = maxLength;
        if (Character.isHighSurrogate(value.charAt(end - 1))
                && end < value.length() && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static void append(StringBuilder target, String value) {
        if (value == null || value.isBlank()) return;
        if (target.length() > 0) target.append('\n');
        target.append(value.trim());
    }

    private static List<String> pack(String text, int maxChars) {
        List<String> values = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String raw : SENTENCE_BOUNDARY_RE.split(text)) {
            String sentence = raw.trim();
            if (sentence.isEmpty()) continue;
            for (String unit : hardSplit(sentence, maxChars)) {
                int separator = buffer.length() == 0 ? 0 : 1;
                if (buffer.length() > 0 && buffer.length() + separator + unit.length() > maxChars) {
                    values.add(buffer.toString());
                    buffer.setLength(0);
                    separator = 0;
                }
                if (separator > 0) buffer.append('\n');
                buffer.append(unit);
            }
        }
        if (buffer.length() > 0) values.add(buffer.toString());
        if (values.isEmpty()) values.add("");
        return values;
    }

    private static List<String> hardSplit(String text, int maxChars) {
        List<String> values = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxChars);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            values.add(text.substring(start, end));
            start = end;
        }
        return values;
    }

    private static String join(String left, String right) {
        if (left == null || left.isBlank()) return right == null ? "" : right.trim();
        if (right == null || right.isBlank()) return left.trim();
        return left.trim() + "\n" + right.trim();
    }

    private static String canonicalQuestion(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\p{P}\\p{S}\\s]+", "").toLowerCase();
    }

    public record FaqEmbeddingPart(int index, String answerPart, String embeddingText) {}
}
