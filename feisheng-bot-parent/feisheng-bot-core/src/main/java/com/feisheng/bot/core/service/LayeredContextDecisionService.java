package com.feisheng.bot.core.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Routes semantic context decisions through fast then deep model validation. */
@Service
public class LayeredContextDecisionService {
    private final IntentUnderstandingService intentUnderstandingService;
    private final DecisionValidator validator;
    private final double fastConfidenceThreshold;

    public LayeredContextDecisionService(
            IntentUnderstandingService intentUnderstandingService,
            DecisionValidator validator,
            @Value("${customer-service.layered-context.fast-confidence:0.80}") double fastConfidenceThreshold) {
        this.intentUnderstandingService = intentUnderstandingService;
        this.validator = validator;
        this.fastConfidenceThreshold = Math.max(0D, Math.min(1D, fastConfidenceThreshold));
    }

    public DecisionResult decide(TurnContext context, Long fastModelId, Long deepModelId) {
        IntentUnderstandingService.ContextModelResult fast = call(context, fastModelId);
        ContextDecision fastDecision = validDecision(context, fast);
        if (fastDecision != null && !requiresDeepModel(fastDecision)) {
            return new DecisionResult(Route.FAST_MODEL, fastDecision, "", fast.latencyMs());
        }

        IntentUnderstandingService.ContextModelResult deep = call(context, deepModelId);
        ContextDecision deepDecision = validDecision(context, deep);
        if (deepDecision != null) {
            return new DecisionResult(Route.DEEP_MODEL, deepDecision, "",
                    fast.latencyMs() + deep.latencyMs());
        }

        String reason = deep == null || !deep.attempted() || "model_unavailable".equals(deep.reasonCode())
                ? "deep_model_unavailable" : "deep_model_invalid";
        return new DecisionResult(Route.FALLBACK, ContextDecision.fallback(context.originalQuery()), reason,
                latency(fast) + latency(deep));
    }

    private IntentUnderstandingService.ContextModelResult call(TurnContext context, Long modelId) {
        try {
            return intentUnderstandingService.decideContext(context, modelId);
        } catch (RuntimeException e) {
            return IntentUnderstandingService.ContextModelResult.failed("model_unavailable", 0L);
        }
    }

    private ContextDecision validDecision(TurnContext context,
                                          IntentUnderstandingService.ContextModelResult result) {
        if (result == null || result.decision() == null) return null;
        try {
            return validator.validate(context, result.decision());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean requiresDeepModel(ContextDecision decision) {
        return decision.needLargeModel() || decision.confidence() < fastConfidenceThreshold
                || decision.relation() == ContextDecision.Relation.MULTI_INTENT
                || decision.relation() == ContextDecision.Relation.UNCERTAIN;
    }

    private long latency(IntentUnderstandingService.ContextModelResult result) {
        return result == null ? 0L : result.latencyMs();
    }

    public enum Route { FAST_MODEL, DEEP_MODEL, FALLBACK }

    public record DecisionResult(Route route, ContextDecision decision, String fallbackReason, long latencyMs) {
        public DecisionResult {
            fallbackReason = fallbackReason == null ? "" : fallbackReason;
            latencyMs = Math.max(0L, latencyMs);
        }
    }
}
