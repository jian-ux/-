package com.feisheng.bot.core.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class RagEvaluationService {
    private final RagRetrievalService retrievalService;

    @Value("${rag.retrieval.pipeline-version:rag-v1}")
    private String pipelineVersion = "rag-v1";

    public RagEvaluationService(RagRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    public EvaluationReport evaluate(EvaluationRequest request) {
        if (request == null || request.cases() == null || request.cases().isEmpty()) {
            throw new IllegalArgumentException("评测样本不能为空");
        }
        validateQualityGate(request.qualityGate());

        int decisionCorrect = 0;
        int answerableTotal = 0;
        int answered = 0;
        int unanswerableTotal = 0;
        int abstained = 0;
        int citationExpected = 0;
        int citationHit = 0;
        int actualAnswered = 0;
        int correctAnswered = 0;
        int actualAbstained = 0;
        int correctAbstained = 0;
        int sourceHitAtOne = 0;
        double reciprocalRankTotal = 0;
        List<CaseResult> results = new ArrayList<>();
        Map<String, Object> pipelineConfiguration = new LinkedHashMap<>();

        for (int i = 0; i < request.cases().size(); i++) {
            EvaluationCase sample = request.cases().get(i);
            validate(sample, i);
            String conversationContext = conversationContext(sample.history());
            RagRetrievalService.RetrievalResult retrieval = hasText(conversationContext)
                ? retrievalService.retrieve(sample.question(), conversationContext, null, false)
                : retrievalService.retrieve(sample.question(), false);
            boolean expectedAnswerable = Boolean.TRUE.equals(sample.answerable());
            boolean correct = retrieval.answerable() == expectedAnswerable
                && decisionMatches(sample.expectedDecision(), retrieval);
            if (correct) decisionCorrect++;
            if (retrieval.answerable()) {
                actualAnswered++;
                if (expectedAnswerable) correctAnswered++;
            } else {
                actualAbstained++;
                if (!expectedAnswerable) correctAbstained++;
            }

            if (expectedAnswerable) {
                answerableTotal++;
                if (retrieval.answerable()) answered++;
            } else {
                unanswerableTotal++;
                if (!retrieval.answerable()) abstained++;
            }

            boolean expectsCitation = hasText(sample.expectedSourceType())
                || sample.expectedSourceId() != null;
            boolean citationMatched = false;
            Integer sourceRank = null;
            if (expectsCitation) {
                citationExpected++;
                citationMatched = citationMatches(sample, retrieval.citations());
                if (citationMatched) citationHit++;
                sourceRank = sourceRank(sample, retrieval.candidates(), retrieval.citations());
                if (sourceRank != null) {
                    reciprocalRankTotal += 1.0 / sourceRank;
                    if (sourceRank == 1) sourceHitAtOne++;
                }
            }

            capturePipelineConfiguration(pipelineConfiguration,
                retrieval.decisionDiagnostics());

            results.add(new CaseResult(
                firstNonBlank(sample.id(), "case-" + (i + 1)),
                sample.question(), expectedAnswerable, retrieval.answerable(),
                correct, retrieval.decision(), retrieval.confidence(),
                Objects.toString(
                    retrieval.decisionDiagnostics().get("reasonCode"), ""),
                retrieval.decisionDiagnostics(),
                rejectionSummary(retrieval.candidates()),
                sample.expectedSourceType(), sample.expectedSourceId(),
                expectsCitation ? citationMatched : null, sourceRank,
                retrieval.citations(), retrieval.candidates(),
                retrieval.structuredUnitDiagnostics()));
        }

        int total = request.cases().size();
        double decisionAccuracy = ratio(decisionCorrect, total);
        double answerRecall = ratio(answered, answerableTotal);
        double noAnswerRecall = ratio(abstained, unanswerableTotal);
        double citationHitRate = ratio(citationHit, citationExpected);
        double answerPrecision = ratio(correctAnswered, actualAnswered);
        double noAnswerPrecision = ratio(correctAbstained, actualAbstained);
        double sourceHitAtOneRate = ratio(sourceHitAtOne, citationExpected);
        double meanReciprocalRank = citationExpected == 0
            ? 0 : round(reciprocalRankTotal / citationExpected);
        QualityGateResult qualityGate = evaluateQualityGate(request.qualityGate(), Map.of(
            "decisionAccuracy", decisionAccuracy,
            "answerRecall", answerRecall,
            "noAnswerRecall", noAnswerRecall,
            "citationHitRate", citationHitRate,
            "answerPrecision", answerPrecision,
            "noAnswerPrecision", noAnswerPrecision,
            "sourceHitAtOneRate", sourceHitAtOneRate,
            "meanReciprocalRank", meanReciprocalRank));
        return new EvaluationReport(
            firstNonBlank(request.name(), "rag-evaluation"),
            UUID.randomUUID().toString(),
            firstNonBlank(request.datasetVersion(), "unversioned"),
            firstNonBlank(pipelineVersion, "rag-v1"), Instant.now().toString(),
            Collections.unmodifiableMap(new LinkedHashMap<>(pipelineConfiguration)),
            total, decisionCorrect, decisionAccuracy, answerableTotal, answered,
            answerRecall, unanswerableTotal, abstained, noAnswerRecall,
            citationExpected, citationHit, citationHitRate,
            actualAnswered, correctAnswered, answerPrecision,
            actualAbstained, correctAbstained, noAnswerPrecision, sourceHitAtOne,
            sourceHitAtOneRate, meanReciprocalRank,
            qualityGate.passed(), qualityGate,
            List.copyOf(results));
    }

    private void validate(EvaluationCase sample, int index) {
        if (sample == null || !hasText(sample.question()) || sample.answerable() == null) {
            throw new IllegalArgumentException("第 " + (index + 1) + " 条样本缺少 question 或 answerable");
        }
        if (sample.history() != null) {
            for (EvaluationTurn turn : sample.history()) {
                if (turn == null || !hasText(turn.content())) {
                    throw new IllegalArgumentException("第 " + (index + 1) + " 条样本包含空历史消息");
                }
            }
        }
    }

    private boolean decisionMatches(String expectedDecision,
                                    RagRetrievalService.RetrievalResult retrieval) {
        if (!hasText(expectedDecision)) return true;
        if ("ANSWER".equalsIgnoreCase(expectedDecision)) return retrieval.answerable();
        if ("NO_ANSWER".equalsIgnoreCase(expectedDecision)
                || "NO_KNOWLEDGE".equalsIgnoreCase(expectedDecision)) {
            return !retrieval.answerable();
        }
        return expectedDecision.equalsIgnoreCase(retrieval.decision());
    }

    private void capturePipelineConfiguration(Map<String, Object> configuration,
                                              Map<String, Object> diagnostics) {
        if (diagnostics == null) return;
        Object thresholds = diagnostics.get("thresholds");
        if (!(thresholds instanceof Map<?, ?> values)) return;
        Map<String, Object> merged = new LinkedHashMap<>();
        Object current = configuration.get("thresholds");
        if (current instanceof Map<?, ?> existing) {
            existing.forEach((key, value) -> merged.put(Objects.toString(key), value));
        }
        values.forEach((key, value) -> merged.put(Objects.toString(key), value));
        configuration.put("thresholds", Collections.unmodifiableMap(merged));
    }

    private Map<String, Integer> rejectionSummary(List<Map<String, Object>> candidates) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        if (candidates == null) return summary;
        for (Map<String, Object> candidate : candidates) {
            Object rawReasons = candidate.get("rejectionReasons");
            if (!(rawReasons instanceof List<?> reasons)) continue;
            for (Object reason : reasons) {
                String value = Objects.toString(reason, "");
                if (!value.isBlank()) summary.merge(value, 1, Integer::sum);
            }
        }
        return Collections.unmodifiableMap(summary);
    }

    private QualityGateResult evaluateQualityGate(QualityGate gate,
                                                   Map<String, Double> metrics) {
        if (gate == null) {
            return new QualityGateResult(false, true, Collections.emptyList());
        }
        validateQualityGate(gate);
        List<QualityGateCheck> checks = new ArrayList<>();
        addMinimum(checks, "decisionAccuracy", metrics, gate.minDecisionAccuracy());
        addMinimum(checks, "answerRecall", metrics, gate.minAnswerRecall());
        addMinimum(checks, "noAnswerRecall", metrics, gate.minNoAnswerRecall());
        addMinimum(checks, "citationHitRate", metrics, gate.minCitationHitRate());
        addMinimum(checks, "answerPrecision", metrics, gate.minAnswerPrecision());
        addMinimum(checks, "noAnswerPrecision", metrics, gate.minNoAnswerPrecision());
        addMinimum(checks, "sourceHitAtOneRate", metrics,
            gate.minSourceHitAtOneRate());
        addMinimum(checks, "meanReciprocalRank", metrics,
            gate.minMeanReciprocalRank());
        boolean passed = checks.stream().allMatch(QualityGateCheck::passed);
        return new QualityGateResult(!checks.isEmpty(), passed, List.copyOf(checks));
    }

    private void addMinimum(List<QualityGateCheck> checks, String metric,
                            Map<String, Double> metrics, Double minimum) {
        if (minimum == null) return;
        double actual = metrics.getOrDefault(metric, 0.0);
        checks.add(new QualityGateCheck(metric, actual, ">=", minimum,
            actual + 0.0000001 >= minimum));
    }

    private void validateQualityGate(QualityGate gate) {
        if (gate == null) return;
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("minDecisionAccuracy", gate.minDecisionAccuracy());
        values.put("minAnswerRecall", gate.minAnswerRecall());
        values.put("minNoAnswerRecall", gate.minNoAnswerRecall());
        values.put("minCitationHitRate", gate.minCitationHitRate());
        values.put("minAnswerPrecision", gate.minAnswerPrecision());
        values.put("minNoAnswerPrecision", gate.minNoAnswerPrecision());
        values.put("minSourceHitAtOneRate", gate.minSourceHitAtOneRate());
        values.put("minMeanReciprocalRank", gate.minMeanReciprocalRank());
        for (Map.Entry<String, Double> value : values.entrySet()) {
            if (value.getValue() != null
                    && (value.getValue() < 0 || value.getValue() > 1)) {
                throw new IllegalArgumentException(
                    value.getKey() + " 必须在 0 到 1 之间");
            }
        }
    }

    private String conversationContext(List<EvaluationTurn> history) {
        if (history == null || history.isEmpty()) return null;
        StringBuilder result = new StringBuilder();
        int start = Math.max(0, history.size() - 6);
        for (int i = start; i < history.size(); i++) {
            EvaluationTurn turn = history.get(i);
            String label = "assistant".equalsIgnoreCase(turn.role()) ? "客服" : "用户";
            result.append(label).append(": ").append(turn.content().trim()).append('\n');
        }
        return result.toString().strip();
    }

    private boolean citationMatches(EvaluationCase sample, List<Map<String, Object>> citations) {
        for (Map<String, Object> citation : citations) {
            boolean typeMatches = !hasText(sample.expectedSourceType())
                || sample.expectedSourceType().equalsIgnoreCase(Objects.toString(citation.get("sourceType"), ""));
            boolean idMatches = sample.expectedSourceId() == null
                || sample.expectedSourceId().toString().equals(Objects.toString(citation.get("sourceId"), ""));
            if (typeMatches && idMatches) return true;
        }
        return false;
    }

    private Integer sourceRank(EvaluationCase sample, List<Map<String, Object>> candidates,
                               List<Map<String, Object>> citations) {
        Integer candidateRank = rankIn(sample, candidates);
        return candidateRank != null ? candidateRank : rankIn(sample, citations);
    }

    private Integer rankIn(EvaluationCase sample, List<Map<String, Object>> values) {
        if (values == null) return null;
        int rank = 0;
        for (Map<String, Object> value : values) {
            if (Boolean.TRUE.equals(value.get("diagnosticOnly"))) continue;
            rank++;
            boolean typeMatches = !hasText(sample.expectedSourceType())
                || sample.expectedSourceType().equalsIgnoreCase(
                    Objects.toString(value.get("sourceType"), ""));
            boolean idMatches = sample.expectedSourceId() == null
                || sample.expectedSourceId().toString().equals(
                    Objects.toString(value.get("sourceId"), ""));
            if (typeMatches && idMatches) return rank;
        }
        return null;
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : Math.round((double) numerator / denominator * 10000) / 10000.0;
    }

    private double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    public record EvaluationRequest(String name, String datasetVersion,
                                    QualityGate qualityGate,
                                    List<EvaluationCase> cases) {
        public EvaluationRequest(String name, List<EvaluationCase> cases) {
            this(name, null, null, cases);
        }
    }

    public record EvaluationCase(String id, String question, Boolean answerable,
                                 String expectedSourceType, Long expectedSourceId,
                                 List<EvaluationTurn> history,
                                 String expectedDecision) {
        public EvaluationCase(String id, String question, Boolean answerable,
                              String expectedSourceType, Long expectedSourceId) {
            this(id, question, answerable, expectedSourceType, expectedSourceId,
                java.util.Collections.emptyList(), null);
        }

        public EvaluationCase(String id, String question, Boolean answerable,
                              String expectedSourceType, Long expectedSourceId,
                              List<EvaluationTurn> history) {
            this(id, question, answerable, expectedSourceType, expectedSourceId,
                history, null);
        }
    }

    public record EvaluationTurn(String role, String content) {}

    public record CaseResult(String id, String question,
                             boolean expectedAnswerable, boolean actualAnswerable,
                             boolean decisionCorrect, String decision, double confidence,
                              String decisionReasonCode,
                              Map<String, Object> decisionDiagnostics,
                              Map<String, Integer> rejectionSummary,
                              String expectedSourceType, Long expectedSourceId,
                              Boolean citationMatched, Integer sourceRank,
                              List<Map<String, Object>> citations,
                              List<Map<String, Object>> candidates,
                              List<Map<String, Object>> structuredUnitDiagnostics) {}

    public record QualityGate(
        Double minDecisionAccuracy,
        Double minAnswerRecall,
        Double minNoAnswerRecall,
        Double minCitationHitRate,
        Double minAnswerPrecision,
        Double minNoAnswerPrecision,
        Double minSourceHitAtOneRate,
        Double minMeanReciprocalRank) {}

    public record QualityGateCheck(String metric, double actual,
                                   String operator, double required,
                                   boolean passed) {}

    public record QualityGateResult(boolean configured, boolean passed,
                                    List<QualityGateCheck> checks) {}

    public record EvaluationReport(String name, String runId,
                                   String datasetVersion, String pipelineVersion,
                                   String evaluatedAt,
                                   Map<String, Object> pipelineConfiguration,
                                   int total, int decisionCorrect, double decisionAccuracy,
                                   int answerableTotal, int answered, double answerRecall,
                                   int unanswerableTotal, int abstained, double noAnswerRecall,
                                   int citationExpected, int citationHit, double citationHitRate,
                                   int actualAnswered, int correctAnswered, double answerPrecision,
                                   int actualAbstained, int correctAbstained, double noAnswerPrecision,
                                   int sourceHitAtOne, double sourceHitAtOneRate,
                                   double meanReciprocalRank,
                                   boolean releaseGatePassed,
                                   QualityGateResult qualityGate,
                                   List<CaseResult> cases) {}
}
