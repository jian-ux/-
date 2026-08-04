package com.feisheng.bot.knowledge.service;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Shared exact-match payload filters for remote and in-memory retrieval. */
public final class PayloadFilters {
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_.-]+");

    private PayloadFilters() {}

    public static boolean matchesPayload(Map<String, Object> payload,
                                         Map<String, Object> filters) {
        return matches(payload, normalize(filters));
    }

    static Map<String, Object> normalize(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return Collections.emptyMap();

        Map<String, Object> normalized = new TreeMap<>();
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (key.isEmpty()) continue;
            if (!VALID_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Invalid payload filter key: " + key);
            }
            Object value = normalizeValue(entry.getValue());
            if (value != null) normalized.put(key, value);
        }
        return normalized.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(normalized);
    }

    static boolean isUnsatisfiable(Map<String, Object> filters) {
        if (filters == null) return false;
        return filters.values().stream()
            .anyMatch(value -> value instanceof Collection<?> collection && collection.isEmpty());
    }

    static boolean matches(Map<String, Object> payload, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return true;
        if (payload == null || isUnsatisfiable(filters)) return false;

        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            Object actual = valueAtPath(payload, filter.getKey());
            Object expected = filter.getValue();
            if (expected instanceof Collection<?> values) {
                if (values.stream().noneMatch(value -> equivalent(actual, value))) return false;
            } else if (!equivalent(actual, expected)) {
                return false;
            }
        }
        return true;
    }

    private static Object valueAtPath(Map<String, Object> payload, String key) {
        Object current = payload;
        for (String segment : key.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) return null;
            current = map.get(segment);
        }
        return current;
    }

    static Map<String, Object> toQdrantFilter(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return Collections.emptyMap();
        List<Map<String, Object>> must = new ArrayList<>(filters.size());
        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            Map<String, Object> match = filter.getValue() instanceof Collection<?> values
                ? Map.of("any", values)
                : Map.of("value", filter.getValue());
            Map<String, Object> condition = new LinkedHashMap<>();
            condition.put("key", filter.getKey());
            condition.put("match", match);
            must.add(condition);
        }
        return Map.of("must", must);
    }

    private static Object normalizeValue(Object value) {
        if (value == null) return null;
        if (value instanceof Collection<?> collection) {
            List<Object> values = new ArrayList<>(collection.size());
            for (Object element : collection) {
                Object normalized = normalizeScalar(element);
                if (normalized != null) values.add(normalized);
            }
            return Collections.unmodifiableList(values);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> values = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                Object normalized = normalizeScalar(Array.get(value, i));
                if (normalized != null) values.add(normalized);
            }
            return Collections.unmodifiableList(values);
        }
        return normalizeScalar(value);
    }

    private static Object normalizeScalar(Object value) {
        if (value == null) return null;
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Date date) return date.toInstant().toString();
        if (value instanceof Instant instant) return instant.toString();
        if (value instanceof Enum<?> enumValue) return enumValue.name();
        throw new IllegalArgumentException(
            "Unsupported payload filter value type: " + value.getClass().getName());
    }

    private static boolean equivalent(Object left, Object right) {
        if (left == null || right == null) return left == right;
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            try {
                return new BigDecimal(leftNumber.toString())
                    .compareTo(new BigDecimal(rightNumber.toString())) == 0;
            } catch (NumberFormatException ignored) {
                return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue()) == 0;
            }
        }
        return left.equals(right);
    }
}
