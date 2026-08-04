package com.feisheng.bot.core.service.impl;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class RagEvaluationService {
    private final RagRetrievalService retrievalService;

    public RagEvaluationService(RagRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    public EvaluationReport evaluate(EvaluationRequest request) {
        if (request == null || request.cases() == null || request.cases().isEmpty()) {
            throw new IllegalArgumentException("评测样本不能为空");
        }

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

        for (int i = 0; i < request.cases().size(); i++) {
            EvaluationCase sample = request.cases().get(i);
            validate(sample, i);
            String conversationContext = conversationContext(sample.history());
            RagRetrievalService.RetrievalResult retrieval = hasText(conversationContext)
                ? retrievalService.retrieve(sample.question(), conversationContext, null, false)
                : retrievalService.retrieve(sample.question(), false);
            boolean expectedAnswerable = Boolean.TRUE.equals(sample.answerable());
            boolean correct = retrieval.answerable() == expectedAnswerable;
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

            results.add(new CaseResult(
                firstNonBlank(sample.id(), "case-" + (i + 1)),
                sample.question(), expectedAnswerable, retrieval.answerable(),
                correct, retrieval.decision(), retrieval.confidence(),
                sample.expectedSourceType(), sample.expectedSourceId(),
                expectsCitation ? citationMatched : null, sourceRank,
                retrieval.citations(), retrieval.candidates(),
                retrieval.structuredUnitDiagnostics()));
        }

        int total = request.cases().size();
        return new EvaluationReport(
            firstNonBlank(request.name(), "rag-evaluation"),
            Instant.now().toString(), total, decisionCorrect,
            ratio(decisionCorrect, total), answerableTotal, answered,
            ratio(answered, answerableTotal), unanswerableTotal, abstained,
            ratio(abstained, unanswerableTotal), citationExpected, citationHit,
            ratio(citationHit, citationExpected), actualAnswered, correctAnswered,
            ratio(correctAnswered, actualAnswered), actualAbstained, correctAbstained,
            ratio(correctAbstained, actualAbstained), sourceHitAtOne,
            ratio(sourceHitAtOne, citationExpected),
            citationExpected == 0 ? 0 : round(reciprocalRankTotal / citationExpected),
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

    public record EvaluationRequest(String name, List<EvaluationCase> cases) {}

    public record EvaluationCase(String id, String question, Boolean answerable,
                                 String expectedSourceType, Long expectedSourceId,
                                 List<EvaluationTurn> history) {
        public EvaluationCase(String id, String question, Boolean answerable,
                              String expectedSourceType, Long expectedSourceId) {
            this(id, question, answerable, expectedSourceType, expectedSourceId,
                java.util.Collections.emptyList());
        }
    }

    public record EvaluationTurn(String role, String content) {}

    public record CaseResult(String id, String question,
                             boolean expectedAnswerable, boolean actualAnswerable,
                             boolean decisionCorrect, String decision, double confidence,
                              String expectedSourceType, Long expectedSourceId,
                              Boolean citationMatched, Integer sourceRank,
                              List<Map<String, Object>> citations,
                              List<Map<String, Object>> candidates,
                              List<Map<String, Object>> structuredUnitDiagnostics) {}

    public record EvaluationReport(String name, String evaluatedAt,
                                   int total, int decisionCorrect, double decisionAccuracy,
                                   int answerableTotal, int answered, double answerRecall,
                                   int unanswerableTotal, int abstained, double noAnswerRecall,
                                   int citationExpected, int citationHit, double citationHitRate,
                                   int actualAnswered, int correctAnswered, double answerPrecision,
                                   int actualAbstained, int correctAbstained, double noAnswerPrecision,
                                   int sourceHitAtOne, double sourceHitAtOneRate,
                                   double meanReciprocalRank,
                                   List<CaseResult> cases) {}
}
