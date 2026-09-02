package com.feisheng.bot.core.service;

import com.feisheng.bot.core.dto.QueryVariant;
import com.feisheng.bot.core.service.impl.CompositeQuestionPlanner;
import com.feisheng.bot.core.service.impl.RagRetrievalService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Coordinates retrieval overload selection while keeping customer context out of cacheable facts. */
public final class DialogRetrievalCoordinator {
    private final RagRetrievalService retrievalService;
    private final CompositeQuestionPlanner compositeQuestionPlanner;

    public DialogRetrievalCoordinator(RagRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
        this.compositeQuestionPlanner = new CompositeQuestionPlanner();
    }

    public RagRetrievalService.RetrievalResult retrieve(
            String primaryQuery,
            List<QueryVariant> supplementalVariants,
            String conversationContext,
            String modalityContext,
            Map<String, Object> filters,
            boolean trackHit) {
        String query = primaryQuery == null ? "" : primaryQuery.trim();
        List<QueryVariant> variants = supplementalVariants == null
            ? Collections.emptyList() : List.copyOf(supplementalVariants);
        Map<String, Object> safeFilters = filters == null ? Collections.emptyMap() : filters;
        CompositeQuestionPlanner.Plan plan = compositeQuestionPlanner.plan(query);
        if (plan.composite()) {
            return retrieveComposite(plan.queries(), conversationContext, modalityContext,
                safeFilters, trackHit);
        }
        return retrieveSingle(query, variants, conversationContext, modalityContext,
            safeFilters, trackHit);
    }

    private RagRetrievalService.RetrievalResult retrieveSingle(
            String query,
            List<QueryVariant> variants,
            String conversationContext,
            String modalityContext,
            Map<String, Object> filters,
            boolean trackHit) {
        if (variants.isEmpty()) {
            if ((conversationContext == null || conversationContext.isBlank())
                    && (modalityContext == null || modalityContext.isBlank())) {
                return retrievalService.retrieve(query, filters, trackHit);
            }
            return retrievalService.retrieve(query, conversationContext, modalityContext,
                filters, trackHit);
        }
        return retrievalService.retrieve(query, conversationContext, modalityContext,
            filters, variants, trackHit);
    }

    private RagRetrievalService.RetrievalResult retrieveComposite(
            List<String> subQuestions,
            String conversationContext,
            String modalityContext,
            Map<String, Object> filters,
            boolean trackHit) {
        List<RagRetrievalService.RetrievalResult> results = new ArrayList<>(subQuestions.size());
        for (String subQuestion : subQuestions) {
            RagRetrievalService.RetrievalResult result = retrieveSingle(subQuestion,
                Collections.emptyList(), conversationContext, modalityContext, filters, trackHit);
            results.add(result == null ? emptyResult() : result);
        }

        int answeredCount = 0;
        boolean semanticAvailable = false;
        double minimumConfidence = Double.POSITIVE_INFINITY;
        Set<Map<String, Object>> citations = new LinkedHashSet<>();
        Set<Map<String, Object>> candidates = new LinkedHashSet<>();
        List<Map<String, Object>> subQuestionDiagnostics = new ArrayList<>(subQuestions.size());
        for (int index = 0; index < subQuestions.size(); index++) {
            RagRetrievalService.RetrievalResult result = results.get(index);
            if (result.answerable()) {
                answeredCount++;
                minimumConfidence = Math.min(minimumConfidence, result.confidence());
                addEvidence(citations, result.citations());
                addEvidence(candidates, result.candidates());
            }
            semanticAvailable = semanticAvailable || result.semanticAvailable();
            Map<String, Object> diagnostic = new LinkedHashMap<>();
            diagnostic.put("index", index + 1);
            diagnostic.put("query", subQuestions.get(index));
            diagnostic.put("answerable", result.answerable());
            diagnostic.put("decision", result.decision());
            diagnostic.put("confidence", result.confidence());
            subQuestionDiagnostics.add(diagnostic);
        }

        boolean answerable = answeredCount > 0;
        boolean complete = answerable && answeredCount == subQuestions.size();
        String decision = complete ? "compound_rag" : answerable ? "partial_rag" : "not_found";
        Map<String, Object> decisionDiagnostics = new LinkedHashMap<>();
        decisionDiagnostics.put("reasonCode", complete ? "COMPOSITE_EVIDENCE_COMPLETE"
            : answerable ? "COMPOSITE_EVIDENCE_PARTIAL" : "COMPOSITE_EVIDENCE_EMPTY");
        decisionDiagnostics.put("composite", true);
        decisionDiagnostics.put("subQuestionCount", subQuestions.size());
        decisionDiagnostics.put("answeredSubQuestionCount", answeredCount);
        decisionDiagnostics.put("subQuestions", List.copyOf(subQuestionDiagnostics));

        return new RagRetrievalService.RetrievalResult(answerable, false, null,
            buildCompositeContext(subQuestions, results),
            answerable ? Math.max(0.0, minimumConfidence) : 0.0,
            decision, semanticAvailable, List.copyOf(citations), List.copyOf(candidates),
            mergeRerankDiagnostics(results), mergeStageLatencies(results), decisionDiagnostics);
    }

    private RagRetrievalService.RetrievalResult emptyResult() {
        return new RagRetrievalService.RetrievalResult(false, false, null, null, 0.0,
            "not_found", false, Collections.emptyList(), Collections.emptyList());
    }

    private void addEvidence(Set<Map<String, Object>> target, List<Map<String, Object>> source) {
        if (source == null) return;
        for (Map<String, Object> item : source) {
            if (item != null) target.add(new LinkedHashMap<>(item));
        }
    }

    private String buildCompositeContext(List<String> subQuestions,
            List<RagRetrievalService.RetrievalResult> results) {
        StringBuilder context = new StringBuilder();
        for (int index = 0; index < subQuestions.size(); index++) {
            if (index > 0) context.append("\n\n");
            RagRetrievalService.RetrievalResult result = results.get(index);
            context.append("【子问题 ").append(index + 1).append("】")
                .append(subQuestions.get(index)).append("\n【可用知识】");
            String evidence = result.context();
            if ((evidence == null || evidence.isBlank())
                    && result.directAnswerText() != null && !result.directAnswerText().isBlank()) {
                evidence = result.directAnswerText();
            }
            context.append(evidence == null || evidence.isBlank() ? "暂无可核实知识" : evidence);
        }
        return context.toString();
    }

    private Map<String, Object> mergeRerankDiagnostics(
            List<RagRetrievalService.RetrievalResult> results) {
        boolean configured = false;
        boolean attempted = false;
        boolean applied = false;
        long latencyMs = 0L;
        String configSource = null;
        for (RagRetrievalService.RetrievalResult result : results) {
            Map<String, Object> diagnostics = result.rerankDiagnostics();
            configured = configured || Boolean.TRUE.equals(diagnostics.get("configured"));
            attempted = attempted || Boolean.TRUE.equals(diagnostics.get("attempted"));
            applied = applied || Boolean.TRUE.equals(diagnostics.get("applied"));
            latencyMs += longValue(diagnostics.get("latencyMs"));
            if (configSource == null && diagnostics.get("configSource") != null) {
                configSource = diagnostics.get("configSource").toString();
            }
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("configured", configured);
        merged.put("attempted", attempted);
        merged.put("applied", applied);
        merged.put("failureReason", !attempted ? "not_attempted"
            : applied ? null : "all_subquestions_degraded");
        merged.put("latencyMs", latencyMs);
        merged.put("scoreSource", applied ? "composite_rerank" : "fused");
        merged.put("configSource", configSource);
        return merged;
    }

    private Map<String, Object> mergeStageLatencies(
            List<RagRetrievalService.RetrievalResult> results) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (RagRetrievalService.RetrievalResult result : results) {
            for (Map.Entry<String, Object> entry : result.stageLatencies().entrySet()) {
                if (entry.getValue() instanceof Number) {
                    merged.merge(entry.getKey(), longValue(entry.getValue()),
                        (left, right) -> longValue(left) + longValue(right));
                }
            }
        }
        merged.put("compositeSubQuestionCount", results.size());
        return merged;
    }

    private long longValue(Object value) {
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }
}
