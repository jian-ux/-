package com.feisheng.bot.core.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits only explicit, independent customer questions. It deliberately keeps
 * ordinary compound nouns and capability statements intact.
 */
public final class CompositeQuestionPlanner {

    private static final int MAX_SUB_QUESTIONS = 3;
    private static final Pattern PUNCTUATION_SEPARATOR = Pattern.compile("[，,；;。！？?!]+");
    private static final Pattern EXPLICIT_QUESTION_CONNECTOR = Pattern.compile(
            "(?:以及|还有|并且|同时|另外|再问|和)(?=(?:点签(?:电子(?:合同|签章))?)?"
                    + "(?:是否|能否|可否|可以|支持|怎么|如何|怎样|哪里|哪儿|哪个|哪些|什么|多少|多久|能不能))");
    private static final Pattern MATERIAL_OR_PROCEDURE_QUESTION = Pattern.compile(
            "(?:需要|要|提供|提交|准备).{0,16}(?:材料|资料|条件|步骤|信息|文件)");
    private static final List<String> QUESTION_MARKERS = List.of(
            "是否", "能否", "可否", "可以", "支持", "怎么", "如何", "怎样",
            "哪里", "哪儿", "哪个", "哪些", "什么", "多少", "多久", "能不能", "吗", "么");
    private static final List<String> DIANQIAN_SUBJECTS = List.of(
            "点签电子合同", "点签电子签章", "点签");

    public Plan plan(String question) {
        String normalized = normalize(question);
        if (normalized.isEmpty()) {
            return new Plan(List.of());
        }

        List<String> clauses = splitPunctuated(normalized);
        if (clauses.size() == 1) {
            clauses = splitExplicitQuestionConnector(normalized);
        }
        if (clauses.size() < 2 || clauses.size() > MAX_SUB_QUESTIONS
                || clauses.stream().anyMatch(clause -> !looksLikeQuestion(clause))) {
            return new Plan(List.of(normalized));
        }

        return new Plan(inheritDianqianSubject(clauses));
    }

    private List<String> splitPunctuated(String question) {
        List<String> clauses = new ArrayList<>();
        for (String part : PUNCTUATION_SEPARATOR.split(question)) {
            String clause = normalize(part);
            if (!clause.isEmpty()) {
                clauses.add(clause);
            }
        }
        return List.copyOf(clauses);
    }

    private List<String> splitExplicitQuestionConnector(String question) {
        List<String> clauses = new ArrayList<>();
        int position = 0;
        var matcher = EXPLICIT_QUESTION_CONNECTOR.matcher(question);
        while (matcher.find()) {
            addClause(clauses, question.substring(position, matcher.start()));
            position = matcher.end();
        }
        addClause(clauses, question.substring(position));
        return List.copyOf(clauses);
    }

    private void addClause(List<String> clauses, String value) {
        String clause = normalize(value);
        if (!clause.isEmpty()) {
            clauses.add(clause);
        }
    }

    private boolean looksLikeQuestion(String clause) {
        return MATERIAL_OR_PROCEDURE_QUESTION.matcher(clause).find()
                || QUESTION_MARKERS.stream().anyMatch(clause::contains);
    }

    private List<String> inheritDianqianSubject(List<String> clauses) {
        String subject = DIANQIAN_SUBJECTS.stream()
                .filter(clauses.get(0)::startsWith)
                .findFirst()
                .orElse(null);
        if (subject == null) {
            return List.copyOf(clauses);
        }

        List<String> resolved = new ArrayList<>(clauses.size());
        for (String clause : clauses) {
            boolean hasExplicitSubject = DIANQIAN_SUBJECTS.stream().anyMatch(clause::contains);
            resolved.add(hasExplicitSubject ? clause : subject + clause);
        }
        return List.copyOf(resolved);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("^[，,；;。！？?!]+|[，,；;。！？?!]+$", "").trim();
    }

    public record Plan(List<String> queries) {
        public Plan {
            queries = queries == null ? List.of() : List.copyOf(queries);
        }

        public boolean composite() {
            return queries.size() > 1;
        }
    }
}
