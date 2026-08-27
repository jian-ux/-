package com.feisheng.bot.core.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ConversationSummaryFormat {
    private static final String UNKNOWN = "未确认";
    private static final List<String> FIELDS = List.of(
        "客户身份",
        "咨询产品",
        "套餐或版本",
        "当前问题",
        "已确认信息",
        "已给出的处理建议",
        "仍待确认信息",
        "当前未解决事项"
    );

    public Optional<String> normalizeModelOutput(String value, int maxChars) {
        Map<String, String> parsed = parseStrict(value);
        if (parsed == null) return Optional.empty();
        return Optional.of(renderWithinLimit(parsed, maxChars));
    }

    public String normalizeStoredSummary(String value, int maxChars) {
        Optional<String> normalized = normalizeModelOutput(value, maxChars);
        if (normalized.isPresent()) return normalized.get();

        String legacy = clean(value);
        if (legacy.isBlank()) return "";
        Map<String, String> migrated = emptyFields();
        migrated.put("当前问题", legacy);
        return renderWithinLimit(migrated, maxChars);
    }

    public String template() {
        Map<String, String> values = new LinkedHashMap<>();
        FIELDS.forEach(field -> values.put(field, UNKNOWN));
        return render(values);
    }

    private Map<String, String> parseStrict(String value) {
        String cleaned = clean(value);
        if (cleaned.isBlank()) return null;

        Map<String, String> parsed = new LinkedHashMap<>();
        for (String rawLine : cleaned.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) continue;

            String matchedField = null;
            String fieldValue = null;
            for (String field : FIELDS) {
                if (line.startsWith(field + "：") || line.startsWith(field + ":")) {
                    matchedField = field;
                    fieldValue = line.substring(field.length() + 1).strip();
                    break;
                }
            }
            if (matchedField != null) {
                int expectedIndex = parsed.size();
                if (expectedIndex >= FIELDS.size()
                        || !FIELDS.get(expectedIndex).equals(matchedField)
                        || fieldValue.isEmpty()) return null;
                parsed.put(matchedField, fieldValue);
                continue;
            }
            return null;
        }

        if (parsed.size() != FIELDS.size()) return null;
        for (String field : FIELDS) {
            if (!parsed.containsKey(field) || parsed.get(field).isBlank()) return null;
        }
        return parsed;
    }

    private String renderWithinLimit(Map<String, String> values, int maxChars) {
        String rendered = render(values);
        int limit = Math.max(1, maxChars);
        if (rendered.length() <= limit) return rendered;

        int fixedChars = FIELDS.stream().mapToInt(field -> field.length() + 1).sum()
            + FIELDS.size() - 1;
        int valueBudget = Math.max(FIELDS.size(), limit - fixedChars);
        List<String> remainingFields = new ArrayList<>(FIELDS);
        Map<String, String> fitted = new LinkedHashMap<>();
        int remainingBudget = valueBudget;
        for (String field : FIELDS) {
            int fairShare = Math.max(1, remainingBudget / remainingFields.size());
            String fittedValue = truncateField(values.get(field), fairShare);
            fitted.put(field, fittedValue);
            remainingBudget = Math.max(0, remainingBudget - fittedValue.length());
            remainingFields.remove(0);
        }
        return render(fitted);
    }

    private String truncateField(String value, int maxChars) {
        String safe = normalizedValue(value);
        if (safe.length() <= maxChars) return safe;
        if (maxChars <= 1) return "…";
        return safe.substring(0, maxChars - 1).stripTrailing() + "…";
    }

    private Map<String, String> emptyFields() {
        Map<String, String> values = new LinkedHashMap<>();
        FIELDS.forEach(field -> values.put(field, UNKNOWN));
        return values;
    }

    private String render(Map<String, String> values) {
        StringBuilder result = new StringBuilder();
        for (String field : FIELDS) {
            if (!result.isEmpty()) result.append('\n');
            result.append(field).append('：').append(normalizedValue(values.get(field)));
        }
        return result.toString();
    }

    private String normalizedValue(String value) {
        return value == null || value.isBlank()
            ? UNKNOWN : value.replaceAll("\\s+", " ").strip();
    }

    private String clean(String value) {
        return value == null ? "" : value.replace("```text", "")
            .replace("```", "").strip();
    }
}
