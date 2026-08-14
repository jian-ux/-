package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.common.util.StructuredTableUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Produces a short answer directly from a parser-confirmed OCR table.
 * The resolver is deliberately extractive: it can select a row and report the
 * recorded check mark, but it never invents a workflow, material, phone rule,
 * or time promise.
 */
final class StructuredTableAnswerResolver {
    private static final List<String> AUTHENTICATION_CUES = List.of(
        "认证", "人脸", "银行卡", "手机号", "手机认证", "人工审核");
    private static final List<String> METHOD_CUES = List.of(
        "怎么", "如何", "哪些", "哪种", "完成", "认证方式", "能否", "能完成");

    private StructuredTableAnswerResolver() {}

    static Optional<Decision> resolve(String query, String standardQuestion, String answer) {
        if (isBlank(query) || !StructuredTableUtil.containsTable(answer)) return Optional.empty();
        StructuredTableUtil.Table table = StructuredTableUtil.parse(answer);
        if (table.rows().isEmpty()) return Optional.empty();

        String normalizedQuery = compact(query);
        String normalizedStandard = compact(standardQuestion);
        boolean exactQuestion = !normalizedStandard.isBlank()
            && normalizedQuery.equals(normalizedStandard);
        RowMatch rowMatch = findRow(table, normalizedQuery);
        boolean relatedGenericQuestion = exactQuestion
            || commonBigramCount(normalizedQuery, normalizedStandard) >= 4;
        if (!rowMatch.found() && !relatedGenericQuestion) return Optional.empty();
        if (!containsAny(normalizedQuery, AUTHENTICATION_CUES)) return Optional.empty();

        if (!rowMatch.found()) {
            return Optional.of(new Decision(renderGenericClarification(table),
                "structured_table_clarification"));
        }

        String subject = rowMatch.subject();
        Map<String, String> row = rowMatch.row();
        List<Field> fields = fields(table, row);
        List<Field> requested = fields.stream()
            .filter(field -> matchesQuery(field, normalizedQuery))
            .sorted(Comparator.comparingInt((Field field) -> queryPosition(field, normalizedQuery))
                .thenComparing(Comparator.comparingInt((Field field) -> field.core().length())
                    .reversed()))
            .toList();
        boolean compositeQuestion = isCompositeQuestion(query);
        if (compositeQuestion && requested.size() < 2) return Optional.empty();

        // A "how can I complete it" question asks for the available methods,
        // not for the value of the phone column merely because it mentions a
        // missing phone number.
        boolean methodQuestion = containsAny(normalizedQuery, METHOD_CUES)
            || normalizedQuery.contains("没有手机号")
            || normalizedQuery.contains("没有大陆手机号");
        if (requested.isEmpty() || methodQuestion) {
            return Optional.of(new Decision(renderSummary(subject, fields),
                "structured_table_row_summary"));
        }
        if (requested.size() > 1) {
            return Optional.of(new Decision(renderFields(subject, requested),
                "structured_table_fields"));
        }
        Field selected = requested.get(0);
        return Optional.of(new Decision(renderField(subject, selected),
            "structured_table_field"));
    }

    private static RowMatch findRow(StructuredTableUtil.Table table, String query) {
        String subjectHeader = table.headers().stream()
            .filter(header -> compact(header).contains("证件类型"))
            .findFirst()
            .orElseGet(() -> table.headers().isEmpty() ? "" : table.headers().get(0));
        if (subjectHeader.isBlank()) return RowMatch.none();

        return table.rows().stream()
            .map(row -> new RowMatch(row.getOrDefault(subjectHeader, "").trim(), row))
            .filter(match -> !match.subject().isBlank()
                && query.contains(compact(match.subject())))
            .max(Comparator.comparingInt(match -> compact(match.subject()).length()))
            .orElseGet(RowMatch::none);
    }

    private static List<Field> fields(StructuredTableUtil.Table table,
                                      Map<String, String> row) {
        List<Field> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String header : table.headers()) {
            if (header == null || header.isBlank() || !row.containsKey(header)) continue;
            String core = fieldCore(header);
            if (core.isBlank() || compact(core).contains("证件类型")
                    || compact(core).equals("序号")) continue;
            if (seen.add(compact(core))) result.add(new Field(core, row.get(header)));
        }
        // Be tolerant of older imports that did not persist the header line.
        if (result.isEmpty()) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String core = fieldCore(entry.getKey());
                if (!core.isBlank() && !compact(core).contains("证件类型")
                        && seen.add(compact(core))) {
                    result.add(new Field(core, entry.getValue()));
                }
            }
        }
        return result;
    }

    private static boolean matchesQuery(Field field, String query) {
        String core = compact(field.core());
        if (query.contains(core)) return true;
        String alias = core.endsWith("认证") ? core.substring(0, core.length() - 2) : core;
        return alias.length() >= 2 && query.contains(alias);
    }

    private static int queryPosition(Field field, String query) {
        String core = compact(field.core());
        int position = query.indexOf(core);
        if (position >= 0) return position;
        String alias = core.endsWith("认证") ? core.substring(0, core.length() - 2) : core;
        position = alias.length() >= 2 ? query.indexOf(alias) : -1;
        return position >= 0 ? position : Integer.MAX_VALUE;
    }

    private static String renderSummary(String subject, List<Field> fields) {
        List<String> supported = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        for (Field field : fields) {
            String value = field.value() == null ? "" : field.value().trim();
            if (value.contains("×")) {
                unsupported.add(field.core());
            } else if (value.contains("√")) {
                supported.add(field.core() + renderDetail(value));
            }
        }
        StringBuilder result = new StringBuilder(subject);
        if (!supported.isEmpty()) result.append("支持").append(joinChinese(supported));
        if (!unsupported.isEmpty()) {
            if (!supported.isEmpty()) result.append("；");
            result.append("不支持").append(joinChinese(unsupported));
        }
        return result.append("。").toString();
    }

    private static String renderGenericClarification(StructuredTableUtil.Table table) {
        boolean everyRowHasSupportedMethod = !table.rows().isEmpty()
            && table.rows().stream().allMatch(row -> fields(table, row).stream()
                .map(Field::value)
                .anyMatch(value -> value != null && value.contains("√")));
        String conclusion = everyRowHasSupportedMethod ? "可以，但" : "";
        return conclusion + "不同证件类型支持的认证方式不同，请提供具体证件类型，"
            + "我再确认可用的认证方式。";
    }

    private static String renderField(String subject, Field field) {
        String value = field.value() == null ? "" : field.value().trim();
        if (value.contains("×")) return subject + "不支持" + field.core() + "。";
        if (value.contains("√")) return subject + "支持" + field.core()
            + renderDetail(value) + "。";
        return subject + "的" + field.core() + "记录为“" + value + "”。";
    }

    private static String renderFields(String subject, List<Field> fields) {
        List<String> clauses = fields.stream()
            .map(field -> renderField(subject, field))
            .map(answer -> answer.endsWith("。")
                ? answer.substring(0, answer.length() - 1) : answer)
            .toList();
        return String.join("；", clauses) + "。";
    }

    private static String renderDetail(String value) {
        int mark = value.indexOf('√');
        if (mark < 0) return "";
        String detail = value.substring(mark + 1).trim();
        if (detail.isBlank()) return "";
        if (detail.startsWith("(") && detail.endsWith(")")) {
            detail = "（" + detail.substring(1, detail.length() - 1).trim() + "）";
        } else if (detail.startsWith("（") && detail.endsWith("）")) {
            detail = detail.trim();
        }
        return detail;
    }

    private static String joinChinese(List<String> values) {
        if (values.size() == 1) return values.get(0);
        if (values.size() == 2) return values.get(0) + "和" + values.get(1);
        return String.join("、", values.subList(0, values.size() - 1))
            + "和" + values.get(values.size() - 1);
    }

    private static String fieldCore(String header) {
        String value = header == null ? "" : header.trim();
        int parenthesis = value.indexOf('(');
        if (parenthesis < 0) parenthesis = value.indexOf('（');
        if (parenthesis > 0) value = value.substring(0, parenthesis);
        return value.replaceAll("\\s+", "").trim();
    }

    private static boolean containsAny(String value, List<String> terms) {
        return terms.stream().anyMatch(value::contains);
    }

    private static boolean isCompositeQuestion(String query) {
        String normalized = compact(query);
        long questionMarks = query.codePoints()
            .filter(value -> value == '?' || value == '？')
            .count();
        return questionMarks >= 2 || containsAny(normalized, List.of(
            "分别", "对比", "区别", "以及", "同时", "还有", "并且", "另外"));
    }

    private static int commonBigramCount(String left, String right) {
        if (left.length() < 2 || right.length() < 2) return 0;
        Set<String> leftBigrams = new LinkedHashSet<>();
        for (int index = 0; index < left.length() - 1; index++) {
            leftBigrams.add(left.substring(index, index + 2));
        }
        int count = 0;
        for (int index = 0; index < right.length() - 1; index++) {
            if (leftBigrams.contains(right.substring(index, index + 2))) count++;
        }
        return count;
    }

    private static String compact(String value) {
        if (value == null) return "";
        return value.toLowerCase()
            .replaceAll("[\\s，。！？?、；;：:（）()【】\\[\\]“”\"'，。]+", "");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record Decision(String answer, String mode) {}
    private record Field(String core, String value) {}
    private record RowMatch(String subject, Map<String, String> row) {
        private boolean found() { return !subject.isBlank() && row != null && !row.isEmpty(); }
        private static RowMatch none() { return new RowMatch("", Map.of()); }
    }
}
