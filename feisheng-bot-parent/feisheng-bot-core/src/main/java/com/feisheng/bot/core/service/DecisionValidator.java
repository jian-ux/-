package com.feisheng.bot.core.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Enforces deterministic isolation and output bounds after model inference. */
@Component
public class DecisionValidator {

    public ContextDecision validate(TurnContext context, ContextDecision decision) {
        if (context == null || decision == null) throw invalid("missing_decision");
        if (decision.relation() == null || decision.taskAction() == null) throw invalid("missing_enum");
        if (!Double.isFinite(decision.confidence()) || decision.confidence() < 0D
                || decision.confidence() > 1D) throw invalid("invalid_confidence");
        if (decision.resolvedQuery().isBlank()) throw invalid("missing_resolved_query");
        if (decision.resolvedQuery().length() > 500) throw invalid("resolved_query_too_long");
        if (decision.originalRequirements().size() > 12
                || decision.originalRequirements().stream().anyMatch(value -> value == null
                || value.isBlank() || value.length() > 160)) throw invalid("invalid_requirements");

        Map<String, ContextCandidate> candidates = new LinkedHashMap<>();
        for (ContextCandidate candidate : context.candidates()) {
            if (candidate == null || candidate.contextId().isBlank()) throw invalid("invalid_candidate");
            candidates.put(candidate.contextId(), candidate);
        }
        validateIds(context, candidates, decision.selectedContextIds(), false);
        validateIds(context, candidates, decision.selectedMemoryIds(), true);
        if (requiresContextCandidate(decision.relation())
                && decision.selectedContextIds().isEmpty()
                && decision.selectedMemoryIds().isEmpty()) {
            throw invalid("missing_selected_context");
        }
        return decision;
    }

    private boolean requiresContextCandidate(ContextDecision.Relation relation) {
        return relation == ContextDecision.Relation.FOLLOW_UP
                || relation == ContextDecision.Relation.CORRECTION
                || relation == ContextDecision.Relation.SLOT_FILL
                || relation == ContextDecision.Relation.RESUME_TASK
                || relation == ContextDecision.Relation.HISTORY_RECALL
                || relation == ContextDecision.Relation.MULTI_INTENT;
    }

    private void validateIds(TurnContext context, Map<String, ContextCandidate> candidates,
                             java.util.List<String> ids, boolean requireMemory) {
        Set<String> seen = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank() || !seen.add(id)) throw invalid("invalid_selected_id");
            ContextCandidate candidate = candidates.get(id);
            if (candidate == null) throw invalid("unknown_selected_id");
            if (!context.channelType().equals(candidate.channelType())
                    || !context.channelUserId().equals(candidate.channelUserId())) {
                throw invalid("cross_customer_candidate");
            }
            if (requireMemory && !isMemory(candidate.sourceType())) throw invalid("invalid_memory_id");
        }
    }

    private boolean isMemory(String sourceType) {
        return "memory_fact".equals(sourceType) || "memory_event".equals(sourceType)
                || "task_slot".equals(sourceType) || "customer_profile".equals(sourceType);
    }

    private IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(reason);
    }
}
