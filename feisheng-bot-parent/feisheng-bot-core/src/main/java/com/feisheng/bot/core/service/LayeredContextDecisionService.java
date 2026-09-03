package com.feisheng.bot.core.service;

import com.feisheng.bot.core.dto.LlmFailureType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Routes semantic context decisions through fast then deep model validation. */
@Service
public class LayeredContextDecisionService {
    private static final Logger log = LoggerFactory.getLogger(LayeredContextDecisionService.class);
    private final IntentUnderstandingService intentUnderstandingService;
    private final DecisionValidator validator;
    private final ContextModelCallPolicy callPolicy;
    private final double fastConfidenceThreshold;

    @Autowired
    public LayeredContextDecisionService(
            IntentUnderstandingService intentUnderstandingService,
            DecisionValidator validator,
            ContextModelCallPolicy callPolicy,
            @Value("${customer-service.layered-context.fast-confidence:0.80}")
            double fastConfidenceThreshold) {
        this.intentUnderstandingService = intentUnderstandingService;
        this.validator = validator;
        this.callPolicy = callPolicy;
        this.fastConfidenceThreshold = Math.max(0D, Math.min(1D, fastConfidenceThreshold));
    }

    public LayeredContextDecisionService(
            IntentUnderstandingService intentUnderstandingService,
            DecisionValidator validator,
            double fastConfidenceThreshold) {
        this(intentUnderstandingService, validator,
                new ContextModelCallPolicy(3_000, 8_000, 4_000, 15_000, 0),
                fastConfidenceThreshold);
    }

    public DecisionResult decide(TurnContext context, Long fastModelId, Long deepModelId) {
        Long effectiveFastModelId = normalizeModelId(fastModelId);
        Long effectiveDeepModelId = normalizeModelId(deepModelId);
        int candidateCount = context == null ? 0 : context.candidates().size();

        IntentUnderstandingService.ContextModelResult fast = call(context, effectiveFastModelId);
        ContextDecision fastDecision = validDecision(context, fast);
        FastAssessment fastAssessment = assessFast(fast, fastDecision);
        if (fastAssessment.outcome() == FastOutcome.ACCEPTED) {
            return new DecisionResult(Route.FAST_MODEL, fastDecision, "", fast.latencyMs(),
                effectiveFastModelId, null, fastAssessment.outcome(),
                fastAssessment.deepTriggerReason(), candidateCount);
        }
        if (Objects.equals(effectiveFastModelId, effectiveDeepModelId)) {
            return new DecisionResult(Route.FALLBACK,
                ContextDecision.fallback(context == null ? "" : context.originalQuery()),
                "deep_model_not_distinct", latency(fast), effectiveFastModelId, null,
                fastAssessment.outcome(), fastAssessment.deepTriggerReason(), candidateCount);
        }

        IntentUnderstandingService.ContextModelResult deep = call(context, effectiveDeepModelId);
        ContextDecision deepDecision = validDecision(context, deep);
        if (deepDecision != null) {
            return new DecisionResult(Route.DEEP_MODEL, deepDecision, "",
                latency(fast) + latency(deep), effectiveFastModelId, effectiveDeepModelId,
                fastAssessment.outcome(), fastAssessment.deepTriggerReason(), candidateCount);
        }

        String reason = deep == null || !deep.attempted()
                || "model_unavailable".equals(deep.reasonCode())
            ? "deep_model_unavailable" : "deep_model_invalid";
        return new DecisionResult(Route.FALLBACK,
            ContextDecision.fallback(context == null ? "" : context.originalQuery()), reason,
            latency(fast) + latency(deep), effectiveFastModelId, effectiveDeepModelId,
            fastAssessment.outcome(), fastAssessment.deepTriggerReason(), candidateCount);
    }

    public DecisionResult decide(TurnContext context, Long fastModelId, Long deepModelId,
                                 Long backupModelId) {
        long deadlineNanos = callPolicy.deadlineFromNow();
        Long effectiveFastModelId = normalizeModelId(fastModelId);
        Long effectiveDeepModelId = normalizeModelId(deepModelId);
        Long effectiveBackupModelId = normalizeModelId(backupModelId);
        int candidateCount = context == null ? 0 : context.candidates().size();

        IntentUnderstandingService.ContextModelResult fast = call(
                context, effectiveFastModelId, ContextModelCallPolicy.Tier.FAST, deadlineNanos);
        ContextDecision fastDecision = validDecision(context, fast);
        FastAssessment fastAssessment = assessFast(fast, fastDecision);
        LlmFailureType fastFailureType = effectiveFailureType(fast, fastDecision);
        if (fastAssessment.outcome() == FastOutcome.ACCEPTED) {
            return detailedResult(Route.FAST_MODEL, fastDecision, "", effectiveFastModelId,
                    null, null, fastAssessment, candidateCount, fast, null, null,
                    fastFailureType, LlmFailureType.NONE, LlmFailureType.NONE, false,
                    deadlineNanos);
        }

        IntentUnderstandingService.ContextModelResult deep = null;
        ContextDecision deepDecision = null;
        LlmFailureType deepFailureType = LlmFailureType.MODEL_UNAVAILABLE;
        if (isDistinct(effectiveDeepModelId, effectiveFastModelId)) {
            deep = call(context, effectiveDeepModelId, ContextModelCallPolicy.Tier.DEEP,
                    deadlineNanos);
            deepDecision = validDecision(context, deep);
            deepFailureType = effectiveFailureType(deep, deepDecision);
            if (deepDecision != null) {
                return detailedResult(Route.DEEP_MODEL, deepDecision, "", effectiveFastModelId,
                        effectiveDeepModelId, null, fastAssessment, candidateCount, fast, deep,
                        null, fastFailureType, LlmFailureType.NONE, LlmFailureType.NONE, false,
                        deadlineNanos);
            }
        }

        IntentUnderstandingService.ContextModelResult backup = null;
        ContextDecision backupDecision = null;
        LlmFailureType backupFailureType = LlmFailureType.NONE;
        if (shouldTryBackup(deepFailureType)
                && isDistinct(effectiveBackupModelId, effectiveFastModelId)
                && isDistinct(effectiveBackupModelId, effectiveDeepModelId)
                && callPolicy.requestTimeoutMs(ContextModelCallPolicy.Tier.BACKUP,
                    deadlineNanos) > 0) {
            backup = call(context, effectiveBackupModelId, ContextModelCallPolicy.Tier.BACKUP,
                    deadlineNanos);
            backupDecision = validDecision(context, backup);
            backupFailureType = effectiveFailureType(backup, backupDecision);
            if (backupDecision != null) {
                return detailedResult(Route.BACKUP_MODEL, backupDecision, "",
                        effectiveFastModelId, effectiveDeepModelId, effectiveBackupModelId,
                        fastAssessment, candidateCount, fast, deep, backup, fastFailureType,
                        deepFailureType, LlmFailureType.NONE, false, deadlineNanos);
            }
        }

        if (fastDecision != null) {
            return detailedResult(Route.FAST_FALLBACK, fastDecision,
                    fallbackReason(deepFailureType, backupFailureType), effectiveFastModelId,
                    effectiveDeepModelId, effectiveBackupModelId, fastAssessment, candidateCount,
                    fast, deep, backup, fastFailureType, deepFailureType, backupFailureType, true,
                    deadlineNanos);
        }
        ContextDecision fallback = ContextDecision.fallback(
                context == null ? "" : context.originalQuery());
        return detailedResult(Route.FALLBACK, fallback,
                fallbackReason(deepFailureType, backupFailureType), effectiveFastModelId,
                effectiveDeepModelId, effectiveBackupModelId, fastAssessment, candidateCount,
                fast, deep, backup, fastFailureType, deepFailureType, backupFailureType, false,
                deadlineNanos);
    }

    private DecisionResult detailedResult(
            Route route,
            ContextDecision decision,
            String fallbackReason,
            Long fastModelId,
            Long deepModelId,
            Long backupModelId,
            FastAssessment fastAssessment,
            int candidateCount,
            IntentUnderstandingService.ContextModelResult fast,
            IntentUnderstandingService.ContextModelResult deep,
            IntentUnderstandingService.ContextModelResult backup,
            LlmFailureType fastFailureType,
            LlmFailureType deepFailureType,
            LlmFailureType backupFailureType,
            boolean usedFastFallback,
            long deadlineNanos) {
        long fastLatencyMs = latency(fast);
        long deepLatencyMs = latency(deep);
        long backupLatencyMs = latency(backup);
        return new DecisionResult(route, decision, fallbackReason,
                fastLatencyMs + deepLatencyMs + backupLatencyMs, fastModelId, deepModelId,
                fastAssessment.outcome(), fastAssessment.deepTriggerReason(), candidateCount,
                backupModelId, fastFailureType, deepFailureType, backupFailureType,
                fastLatencyMs, deepLatencyMs, backupLatencyMs, usedFastFallback,
                System.nanoTime() >= deadlineNanos);
    }

    private boolean isDistinct(Long candidate, Long other) {
        return candidate != null && !Objects.equals(candidate, other);
    }

    private boolean shouldTryBackup(LlmFailureType failureType) {
        return switch (failureType == null ? LlmFailureType.MODEL_UNAVAILABLE : failureType) {
            case TIMEOUT, RATE_LIMIT, SERVER_ERROR, INVALID_OUTPUT, MODEL_UNAVAILABLE,
                    CIRCUIT_OPEN -> true;
            default -> false;
        };
    }

    private LlmFailureType effectiveFailureType(
            IntentUnderstandingService.ContextModelResult result,
            ContextDecision decision) {
        if (decision != null) {
            return LlmFailureType.NONE;
        }
        if (result == null || !result.attempted()) {
            return LlmFailureType.MODEL_UNAVAILABLE;
        }
        if (result.failureType() != null && result.failureType() != LlmFailureType.NONE) {
            return result.failureType();
        }
        return LlmFailureType.INVALID_OUTPUT;
    }

    private String fallbackReason(LlmFailureType deepFailureType,
                                  LlmFailureType backupFailureType) {
        LlmFailureType terminal = backupFailureType != null
                && backupFailureType != LlmFailureType.NONE
                ? backupFailureType : deepFailureType;
        return terminal == null ? "model_unavailable"
                : terminal.name().toLowerCase(java.util.Locale.ROOT);
    }

    private IntentUnderstandingService.ContextModelResult call(TurnContext context, Long modelId) {
        try {
            return intentUnderstandingService.decideContext(context, modelId);
        } catch (RuntimeException e) {
            return IntentUnderstandingService.ContextModelResult.failed("model_unavailable", 0L);
        }
    }

    private IntentUnderstandingService.ContextModelResult call(
            TurnContext context,
            Long modelId,
            ContextModelCallPolicy.Tier tier,
            long deadlineNanos) {
        if (modelId == null) {
            return IntentUnderstandingService.ContextModelResult.failed("model_unavailable", 0L,
                    LlmFailureType.MODEL_UNAVAILABLE, false, false);
        }
        try {
            return intentUnderstandingService.decideContext(context, modelId, tier, deadlineNanos);
        } catch (RuntimeException exception) {
            return IntentUnderstandingService.ContextModelResult.failed("model_unavailable", 0L,
                    LlmFailureType.MODEL_UNAVAILABLE, false, false);
        }
    }

    private ContextDecision validDecision(
            TurnContext context,
            IntentUnderstandingService.ContextModelResult result) {
        if (result == null || result.decision() == null) {
            return null;
        }
        try {
            return validator.validate(context, result.decision());
        } catch (IllegalArgumentException e) {
            log.warn("Layered context decision rejected by validator: {}", e.getMessage());
            return null;
        }
    }

    private FastAssessment assessFast(
            IntentUnderstandingService.ContextModelResult result,
            ContextDecision decision) {
        if (decision == null) {
            if (result == null || !result.attempted()) {
                return new FastAssessment(FastOutcome.NOT_ATTEMPTED,
                    DeepTriggerReason.FAST_MODEL_NOT_ATTEMPTED);
            }
            if ("model_unavailable".equals(result.reasonCode())) {
                return new FastAssessment(FastOutcome.UNAVAILABLE,
                    DeepTriggerReason.FAST_MODEL_UNAVAILABLE);
            }
            return new FastAssessment(FastOutcome.INVALID,
                DeepTriggerReason.INVALID_FAST_DECISION);
        }
        if (decision.relation() == ContextDecision.Relation.MULTI_INTENT
                || decision.relation() == ContextDecision.Relation.UNCERTAIN) {
            return new FastAssessment(FastOutcome.ESCALATED,
                DeepTriggerReason.COMPLEX_RELATION);
        }
        if (decision.confidence() < fastConfidenceThreshold) {
            return new FastAssessment(FastOutcome.ESCALATED,
                DeepTriggerReason.LOW_CONFIDENCE);
        }
        if (decision.needLargeModel()) {
            return new FastAssessment(FastOutcome.ESCALATED,
                DeepTriggerReason.MODEL_REQUESTED);
        }
        return new FastAssessment(FastOutcome.ACCEPTED, DeepTriggerReason.NONE);
    }

    private Long normalizeModelId(Long modelId) {
        return modelId != null && modelId > 0 ? modelId : null;
    }

    private long latency(IntentUnderstandingService.ContextModelResult result) {
        return result == null ? 0L : Math.max(0L, result.latencyMs());
    }

    public enum Route {
        FAST_MODEL,
        DEEP_MODEL,
        BACKUP_MODEL,
        FAST_FALLBACK,
        FALLBACK
    }

    public enum FastOutcome {
        ACCEPTED,
        ESCALATED,
        INVALID,
        UNAVAILABLE,
        NOT_ATTEMPTED
    }

    public enum DeepTriggerReason {
        NONE,
        LOW_CONFIDENCE,
        MODEL_REQUESTED,
        COMPLEX_RELATION,
        INVALID_FAST_DECISION,
        FAST_MODEL_UNAVAILABLE,
        FAST_MODEL_NOT_ATTEMPTED
    }

    private record FastAssessment(FastOutcome outcome, DeepTriggerReason deepTriggerReason) {}

    public record DecisionResult(
            Route route,
            ContextDecision decision,
            String fallbackReason,
            long latencyMs,
            Long fastModelId,
            Long deepModelId,
            FastOutcome fastOutcome,
            DeepTriggerReason deepTriggerReason,
            int candidateCount,
            Long backupModelId,
            LlmFailureType fastFailureType,
            LlmFailureType deepFailureType,
            LlmFailureType backupFailureType,
            long fastLatencyMs,
            long deepLatencyMs,
            long backupLatencyMs,
            boolean usedFastFallback,
            boolean deadlineExceeded) {
        public DecisionResult(Route route, ContextDecision decision, String fallbackReason,
                              long latencyMs, Long fastModelId, Long deepModelId,
                              FastOutcome fastOutcome, DeepTriggerReason deepTriggerReason,
                              int candidateCount) {
            this(route, decision, fallbackReason, latencyMs, fastModelId, deepModelId,
                    fastOutcome, deepTriggerReason, candidateCount, null, LlmFailureType.NONE,
                    LlmFailureType.NONE, LlmFailureType.NONE, latencyMs, 0L, 0L, false, false);
        }

        public DecisionResult {
            fallbackReason = fallbackReason == null ? "" : fallbackReason;
            latencyMs = Math.max(0L, latencyMs);
            fastOutcome = fastOutcome == null ? FastOutcome.NOT_ATTEMPTED : fastOutcome;
            deepTriggerReason = deepTriggerReason == null
                ? DeepTriggerReason.NONE : deepTriggerReason;
            candidateCount = Math.max(0, candidateCount);
            fastFailureType = fastFailureType == null ? LlmFailureType.NONE : fastFailureType;
            deepFailureType = deepFailureType == null ? LlmFailureType.NONE : deepFailureType;
            backupFailureType = backupFailureType == null ? LlmFailureType.NONE : backupFailureType;
            fastLatencyMs = Math.max(0L, fastLatencyMs);
            deepLatencyMs = Math.max(0L, deepLatencyMs);
            backupLatencyMs = Math.max(0L, backupLatencyMs);
        }
    }
}
