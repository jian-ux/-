package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotFaqRegressionRun;
import com.feisheng.bot.admin.entity.BotUnmatchedQuestion;
import com.feisheng.bot.admin.mapper.BotFaqRegressionRunMapper;
import com.feisheng.bot.admin.mapper.BotUnmatchedQuestionMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class BadCaseQualityService {
    private static final int HISTORY_LIMIT = 20;
    private static final int REPEATED_FAILURE_LIMIT = 10;

    private final BotUnmatchedQuestionMapper unmatchedMapper;
    private final BotFaqRegressionRunMapper regressionRunMapper;
    private final ObjectMapper objectMapper;

    public BadCaseQualityService(BotUnmatchedQuestionMapper unmatchedMapper,
                                 BotFaqRegressionRunMapper regressionRunMapper,
                                 ObjectMapper objectMapper) {
        this.unmatchedMapper = unmatchedMapper;
        this.regressionRunMapper = regressionRunMapper;
        this.objectMapper = objectMapper;
    }

    public QualitySummary summarize() {
        List<BotUnmatchedQuestion> questions = unmatchedMapper.selectList(
            new QueryWrapper<BotUnmatchedQuestion>()
                .select("similar_count", "is_resolved", "trigger_types",
                    "last_answer_status", "last_answer_decision",
                    "review_status", "review_correct"));
        List<BotFaqRegressionRun> runs = regressionRunMapper.selectList(
            new QueryWrapper<BotFaqRegressionRun>()
                .orderByDesc("create_time")
                .orderByDesc("id")
                .last("LIMIT " + HISTORY_LIMIT));

        int totalOccurrences = 0;
        int pending = 0;
        int reviewed = 0;
        int reviewedCorrect = 0;
        Map<String, Integer> triggerCounts = new LinkedHashMap<>();
        Map<String, Integer> decisionCounts = new LinkedHashMap<>();
        for (BotUnmatchedQuestion question : questions) {
            int occurrences = Math.max(question.getSimilarCount() == null
                ? 1 : question.getSimilarCount(), 1);
            totalOccurrences += occurrences;
            if (!Integer.valueOf(1).equals(question.getIsResolved())) pending++;
            if ("REVIEWED".equalsIgnoreCase(question.getReviewStatus())) {
                reviewed++;
                if (Integer.valueOf(1).equals(question.getReviewCorrect())) {
                    reviewedCorrect++;
                }
            }
            for (String trigger : triggers(question.getTriggerTypes())) {
                triggerCounts.merge(trigger, occurrences, Integer::sum);
            }
            decisionCounts.merge(decision(question), occurrences, Integer::sum);
        }

        List<TriggerMetric> triggers = triggerCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .map(entry -> new TriggerMetric(entry.getKey(), entry.getValue()))
            .toList();
        List<DecisionMetric> decisions = decisionCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .map(entry -> new DecisionMetric(entry.getKey(), entry.getValue()))
            .toList();
        List<RegressionRunView> history = runs.stream().map(this::toView).toList();
        Double passRateDelta = history.size() < 2 ? null
            : rate((int) Math.round((history.get(0).passRate() - history.get(1).passRate()) * 1000), 1000);
        return new QualitySummary(questions.size(), pending, questions.size() - pending,
            totalOccurrences, rate(questions.size() - pending, questions.size()), triggers,
            history, passRateDelta, repeatedFailures(runs), reviewed,
            reviewedCorrect, rate(reviewedCorrect, reviewed), questions.size() - reviewed,
            decisions);
    }

    private String decision(BotUnmatchedQuestion question) {
        String raw = question.getLastAnswerDecision();
        if (raw == null || raw.isBlank()) raw = question.getLastAnswerStatus();
        if (raw == null || raw.isBlank()) return "UNKNOWN";
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "ANSWER", "ANSWER_PARTIAL", "ANSWERED" -> "ANSWER";
            case "CLARIFY", "CLARIFICATION" -> "CLARIFY";
            case "HANDOFF", "HANDOFF_REQUESTED", "HUMAN_HANDLING", "BLOCKED" -> "HANDOFF";
            case "NO_ANSWER", "NO_KNOWLEDGE", "ERROR", "OUT_OF_SCOPE" -> "NO_ANSWER";
            default -> "UNKNOWN";
        };
    }

    private List<String> triggers(String value) {
        if (value == null || value.isBlank()) return List.of("NO_ANSWER");
        List<String> result = Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .distinct()
            .toList();
        return result.isEmpty() ? List.of("NO_ANSWER") : result;
    }

    private RegressionRunView toView(BotFaqRegressionRun run) {
        return new RegressionRunView(run.getId(), Integer.valueOf(1).equals(run.getPassed()),
            value(run.getPublishedDraftCount()), value(run.getDatasetCaseCount()),
            value(run.getExecutedCaseCount()), value(run.getPassedCaseCount()),
            value(run.getFailedCaseCount()), Integer.valueOf(1).equals(run.getTruncated()),
            rate(value(run.getPassedCaseCount()), value(run.getExecutedCaseCount())),
            run.getPromptVersion(), run.getCreateTime());
    }

    private List<RepeatedFailure> repeatedFailures(List<BotFaqRegressionRun> runs) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BotFaqRegressionRun run : runs) {
            Set<String> seenInRun = new HashSet<>();
            for (FaqRegressionService.FailureSnapshot failure : failures(run.getFailedCasesJson())) {
                String question = failure.question() == null ? "" : failure.question().trim();
                if (!question.isBlank() && seenInRun.add(question)) counts.merge(question, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
            .filter(entry -> entry.getValue() >= 2)
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .limit(REPEATED_FAILURE_LIMIT)
            .map(entry -> new RepeatedFailure(entry.getKey(), entry.getValue()))
            .toList();
    }

    private List<FaqRegressionService.FailureSnapshot> failures(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                new TypeReference<List<FaqRegressionService.FailureSnapshot>>() {});
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private double rate(int numerator, int denominator) {
        return denominator == 0 ? 0 : Math.round(numerator * 1000.0 / denominator) / 1000.0;
    }

    public record QualitySummary(int questionCount, int pendingQuestionCount,
                                 int resolvedQuestionCount, int totalOccurrenceCount,
                                 double resolutionRate, List<TriggerMetric> triggerCounts,
                                  List<RegressionRunView> regressionHistory,
                                  Double passRateDelta,
                                  List<RepeatedFailure> repeatedFailures,
                                  int reviewedQuestionCount, int reviewedCorrectCount,
                                  double decisionAccuracy, int pendingReviewQuestionCount,
                                  List<DecisionMetric> decisionCounts) {}
    public record TriggerMetric(String triggerType, int count) {}
    public record DecisionMetric(String decision, int count) {}
    public record RegressionRunView(Long id, boolean passed, int publishedDraftCount,
                                    int datasetCaseCount, int executedCaseCount,
                                    int passedCaseCount, int failedCaseCount,
                                    boolean truncated, double passRate,
                                    String promptVersion, Date createTime) {}
    public record RepeatedFailure(String question, int failedRunCount) {}
}
