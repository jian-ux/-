package com.feisheng.bot.core.service;

import com.feisheng.bot.core.dto.LlmFailureType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LayeredContextDecisionServiceTest {

    @Mock
    private IntentUnderstandingService intentUnderstandingService;

    @Test
    void acceptsHighConfidenceFastModelDecisionAndPreservesCurrentRequirements() {
        TurnContext context = context(List.of(candidate("task:active", "active_task", "点签的使用教程")));
        ContextDecision fast = decision(ContextDecision.Relation.FOLLOW_UP, List.of("task:active"),
                List.of(), List.of("需要视频形式的教程"), "点签是否提供使用视频教程？", 0.93, false);
        when(intentUnderstandingService.decideContext(eq(context), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
                .thenReturn(IntentUnderstandingService.ContextModelResult.success(fast, 10L));

        LayeredContextDecisionService.DecisionResult result = service().decide(context, 11L, 22L);

        assertEquals(LayeredContextDecisionService.Route.FAST_MODEL, result.route());
        assertEquals(11L, result.fastModelId());
        assertNull(result.deepModelId());
        assertEquals(LayeredContextDecisionService.FastOutcome.ACCEPTED, result.fastOutcome());
        assertEquals(LayeredContextDecisionService.DeepTriggerReason.NONE, result.deepTriggerReason());
        assertEquals(1, result.candidateCount());
        assertEquals("有没有视频的？", context.originalQuery());
        assertEquals(List.of("需要视频形式的教程"), result.decision().originalRequirements());
        assertEquals("点签是否提供使用视频教程？", result.decision().resolvedQuery());
        verify(intentUnderstandingService).decideContext(eq(context), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong());
        verify(intentUnderstandingService).recordContextDecisionOutcome(
                11L, 10L, LlmFailureType.NONE);
        verifyNoMoreInteractions(intentUnderstandingService);
    }

    @Test
    void escalatesLowConfidenceFastDecisionToDeepModel() {
        TurnContext context = context(List.of(candidate("message:9", "recent_message", "点签的使用教程")));
        ContextDecision fast = decision(ContextDecision.Relation.FOLLOW_UP, List.of("message:9"),
                List.of(), List.of("视频教程"), "点签的视频教程", 0.55, true);
        ContextDecision deep = decision(ContextDecision.Relation.FOLLOW_UP, List.of("message:9"),
                List.of(), List.of("需要视频形式的教程"), "点签是否提供使用视频教程？", 0.91, false);
        when(intentUnderstandingService.decideContext(eq(context), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
                .thenReturn(IntentUnderstandingService.ContextModelResult.success(fast, 8L));
        when(intentUnderstandingService.decideContext(eq(context), eq(22L),
                eq(ContextModelCallPolicy.Tier.DEEP), anyLong()))
                .thenReturn(IntentUnderstandingService.ContextModelResult.success(deep, 20L));

        LayeredContextDecisionService.DecisionResult result = service().decide(context, 11L, 22L);

        assertEquals(LayeredContextDecisionService.Route.DEEP_MODEL, result.route());
        assertEquals(11L, result.fastModelId());
        assertEquals(22L, result.deepModelId());
        assertEquals(LayeredContextDecisionService.FastOutcome.ESCALATED, result.fastOutcome());
        assertEquals(LayeredContextDecisionService.DeepTriggerReason.LOW_CONFIDENCE,
            result.deepTriggerReason());
        assertEquals(deep, result.decision());
    }

    @Test
    void fallsBackWithoutCallingTheSameModelTwice() {
        TurnContext context = context(List.of(candidate("message:9", "recent_message", "点签的使用教程")));
        ContextDecision fast = decision(ContextDecision.Relation.FOLLOW_UP, List.of("message:9"),
            List.of(), List.of("视频教程"), "点签的视频教程", 0.55, true);
        when(intentUnderstandingService.decideContext(eq(context), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
            .thenReturn(IntentUnderstandingService.ContextModelResult.success(fast, 8L));

        LayeredContextDecisionService.DecisionResult result = service().decide(context, 11L, 11L);

        assertEquals(LayeredContextDecisionService.Route.FAST_FALLBACK, result.route());
        assertEquals("model_unavailable", result.fallbackReason());
        assertEquals(11L, result.fastModelId());
        assertNull(result.deepModelId());
        assertEquals(LayeredContextDecisionService.DeepTriggerReason.LOW_CONFIDENCE,
            result.deepTriggerReason());
        verify(intentUnderstandingService).decideContext(eq(context), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong());
        verify(intentUnderstandingService).recordContextDecisionOutcome(
                11L, 8L, LlmFailureType.NONE);
        verifyNoMoreInteractions(intentUnderstandingService);
    }

    @Test
    void keepsValidatedFastDecisionWhenPrimaryDeepModelTimesOut() {
        TurnContext context = context(List.of(candidate("message:9", "recent_message", "点签的使用教程")));
        ContextDecision fast = decision(ContextDecision.Relation.FOLLOW_UP, List.of("message:9"),
                List.of(), List.of("视频教程"), "点签的视频教程", 0.55, true);
        when(intentUnderstandingService.decideContext(eq(context), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
                .thenReturn(IntentUnderstandingService.ContextModelResult.success(fast, 8L));
        when(intentUnderstandingService.decideContext(eq(context), eq(22L),
                eq(ContextModelCallPolicy.Tier.DEEP), anyLong()))
                .thenReturn(IntentUnderstandingService.ContextModelResult.failed(
                        "timeout", 3_000L, LlmFailureType.TIMEOUT, false, false));

        LayeredContextDecisionService.DecisionResult result = boundedService()
                .decide(context, 11L, 22L, null);

        assertEquals(LayeredContextDecisionService.Route.FAST_FALLBACK, result.route());
        assertEquals("点签的视频教程", result.decision().resolvedQuery());
        assertTrue(result.usedFastFallback());
        assertEquals(LlmFailureType.TIMEOUT, result.deepFailureType());
        assertEquals(3_008L, result.latencyMs());
    }

    @Test
    void usesDistinctBackupModelWhenPrimaryDeepModelIsRateLimited() {
        TurnContext context = context(List.of(candidate("message:9", "recent_message", "点签的使用教程")));
        ContextDecision fast = decision(ContextDecision.Relation.FOLLOW_UP, List.of("message:9"),
                List.of(), List.of("视频教程"), "点签的视频教程", 0.55, true);
        ContextDecision backup = decision(ContextDecision.Relation.FOLLOW_UP, List.of("message:9"),
                List.of(), List.of("视频教程"), "点签是否提供使用视频教程？", 0.93, false);
        when(intentUnderstandingService.decideContext(eq(context), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
                .thenReturn(IntentUnderstandingService.ContextModelResult.success(fast, 8L));
        when(intentUnderstandingService.decideContext(eq(context), eq(22L),
                eq(ContextModelCallPolicy.Tier.DEEP), anyLong()))
                .thenReturn(IntentUnderstandingService.ContextModelResult.failed(
                        "rate_limit", 15L, LlmFailureType.RATE_LIMIT, false, false));
        when(intentUnderstandingService.decideContext(eq(context), eq(33L),
                eq(ContextModelCallPolicy.Tier.BACKUP), anyLong()))
                .thenReturn(IntentUnderstandingService.ContextModelResult.success(backup, 20L));

        LayeredContextDecisionService.DecisionResult result = boundedService()
                .decide(context, 11L, 22L, 33L);

        assertEquals(LayeredContextDecisionService.Route.BACKUP_MODEL, result.route());
        assertEquals(33L, result.backupModelId());
        assertEquals(backup, result.decision());
        assertEquals(LlmFailureType.RATE_LIMIT, result.deepFailureType());
        assertEquals(20L, result.backupLatencyMs());
    }

    @Test
    void escalatesMultiIntentEvenWhenFastConfidenceIsHigh() {
        TurnContext context = context(List.of(candidate("message:9", "recent_message", "点签的使用教程")));
        ContextDecision fast = decision(ContextDecision.Relation.MULTI_INTENT, List.of("message:9"),
                List.of(), List.of("视频教程", "价格"), "点签视频教程和价格", 0.96, false);
        ContextDecision deep = decision(ContextDecision.Relation.MULTI_INTENT, List.of("message:9"),
                List.of(), List.of("视频教程", "价格"), "点签是否有视频教程，价格如何？", 0.90, false);
        when(intentUnderstandingService.decideContext(eq(context), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
                .thenReturn(IntentUnderstandingService.ContextModelResult.success(fast, 8L));
        when(intentUnderstandingService.decideContext(eq(context), eq(22L),
                eq(ContextModelCallPolicy.Tier.DEEP), anyLong()))
                .thenReturn(IntentUnderstandingService.ContextModelResult.success(deep, 20L));

        assertEquals(LayeredContextDecisionService.Route.DEEP_MODEL,
                service().decide(context, 11L, 22L).route());
    }

    @Test
    void validatorRejectsUnknownAndCrossCustomerCandidateIds() {
        TurnContext context = context(List.of(candidate("message:9", "recent_message", "历史问题")));
        DecisionValidator validator = new DecisionValidator();

        ContextDecision unknown = decision(ContextDecision.Relation.FOLLOW_UP, List.of("message:404"),
                List.of(), List.of(), "完整问题", 0.90, false);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(context, unknown));

        ContextCandidate otherCustomer = new ContextCandidate("memory:other", "memory_fact", "其他客户信息",
                null, null, "web", "other-customer", 0.9D, null, null, "long_term_memory");
        assertThrows(IllegalArgumentException.class, () -> context(List.of(otherCustomer)));
    }

    @Test
    void validatorRejectsContextDependentDecisionWithoutSelectedCandidate() {
        TurnContext context = context(List.of(candidate("message:9", "recent_message", "历史问题")));
        DecisionValidator validator = new DecisionValidator();
        ContextDecision followUpWithoutEvidence = decision(
            ContextDecision.Relation.FOLLOW_UP, List.of(), List.of(), List.of("视频形式"),
            "凭空补出的历史问题", 0.95, false);

        assertThrows(IllegalArgumentException.class,
            () -> validator.validate(context, followUpWithoutEvidence));
    }

    @Test
    void turnContextRejectsCandidatesBeyondTheModelPromptBudget() {
        List<ContextCandidate> candidates = java.util.stream.IntStream.range(0, 13)
                .mapToObj(index -> candidate("message:" + index, "recent_message", "历史问题" + index))
                .toList();

        assertThrows(IllegalArgumentException.class, () -> context(candidates));
    }

    @Test
    void fallsBackConservativelyWhenModelsFailOrReturnInvalidIds() {
        TurnContext context = context(List.of(candidate("message:9", "recent_message", "点签的使用教程")));
        ContextDecision invalid = decision(ContextDecision.Relation.FOLLOW_UP, List.of("message:404"),
                List.of(), List.of("视频形式"), "错误补全", 0.95, false);
        when(intentUnderstandingService.decideContext(eq(context), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
                .thenReturn(IntentUnderstandingService.ContextModelResult.success(invalid, 8L));
        when(intentUnderstandingService.decideContext(eq(context), eq(22L),
                eq(ContextModelCallPolicy.Tier.DEEP), anyLong()))
                .thenReturn(IntentUnderstandingService.ContextModelResult.failed(
                        "model_unavailable", 20L, LlmFailureType.MODEL_UNAVAILABLE,
                        false, false));

        LayeredContextDecisionService.DecisionResult result = service().decide(context, 11L, 22L);

        assertEquals(LayeredContextDecisionService.Route.FALLBACK, result.route());
        assertEquals(11L, result.fastModelId());
        assertEquals(22L, result.deepModelId());
        assertEquals(LayeredContextDecisionService.FastOutcome.INVALID, result.fastOutcome());
        assertEquals(LayeredContextDecisionService.DeepTriggerReason.INVALID_FAST_DECISION,
            result.deepTriggerReason());
        assertEquals(1, result.candidateCount());
        assertEquals("有没有视频的？", result.decision().resolvedQuery());
        assertEquals(List.of(), result.decision().selectedContextIds());
        assertEquals("deep_model_unavailable", result.fallbackReason());
    }

    @Test
    void reportsDeepCircuitOpenInDecisionDiagnostics() {
        TurnContext context = context(List.of(candidate(
                "message:9", "recent_message", "点签的使用教程")));
        ContextDecision fast = decision(ContextDecision.Relation.FOLLOW_UP,
                List.of("message:9"), List.of(), List.of("视频教程"),
                "点签的视频教程", 0.55, true);
        when(intentUnderstandingService.decideContext(eq(context), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
                .thenReturn(IntentUnderstandingService.ContextModelResult.success(fast, 8L));
        when(intentUnderstandingService.decideContext(eq(context), eq(22L),
                eq(ContextModelCallPolicy.Tier.DEEP), anyLong()))
                .thenReturn(IntentUnderstandingService.ContextModelResult.failed(
                        "circuit_open", 0L, LlmFailureType.CIRCUIT_OPEN, false, true));

        LayeredContextDecisionService.DecisionResult result = boundedService()
                .decide(context, 11L, 22L, null);

        assertEquals(LayeredContextDecisionService.CircuitState.DEEP_OPEN,
                result.circuitState());
        assertEquals(LlmFailureType.CIRCUIT_OPEN, result.deepFailureType());
    }

    @Test
    void threeArgumentEntryUsesBoundedCascadeAndPreservesFastFallback() {
        TurnContext context = context(List.of(candidate(
                "message:9", "recent_message", "点签的使用教程")));
        ContextDecision fast = decision(ContextDecision.Relation.FOLLOW_UP,
                List.of("message:9"), List.of(), List.of("视频教程"),
                "点签的视频教程", 0.55, true);
        when(intentUnderstandingService.decideContext(eq(context), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
                .thenReturn(IntentUnderstandingService.ContextModelResult.success(fast, 8L));
        when(intentUnderstandingService.decideContext(eq(context), eq(22L),
                eq(ContextModelCallPolicy.Tier.DEEP), anyLong()))
                .thenReturn(IntentUnderstandingService.ContextModelResult.failed(
                        "timeout", 3_000L, LlmFailureType.TIMEOUT, false, false));

        LayeredContextDecisionService.DecisionResult result = boundedService()
                .decide(context, 11L, 22L);

        assertEquals(LayeredContextDecisionService.Route.FAST_FALLBACK, result.route());
        assertEquals("点签的视频教程", result.decision().resolvedQuery());
        assertTrue(result.usedFastFallback());
    }

    @Test
    void validatorRejectionIsReportedAsInvalidModelOutcome() {
        TurnContext context = context(List.of(candidate(
                "message:9", "recent_message", "点签的使用教程")));
        ContextDecision invalid = decision(ContextDecision.Relation.FOLLOW_UP,
                List.of("message:404"), List.of(), List.of("视频形式"),
                "错误补全", 0.95, false);
        when(intentUnderstandingService.decideContext(eq(context), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
                .thenReturn(IntentUnderstandingService.ContextModelResult.success(invalid, 8L));

        boundedService().decide(context, 11L, null, null);

        verify(intentUnderstandingService).recordContextDecisionOutcome(
                11L, 8L, LlmFailureType.INVALID_OUTPUT);
    }

    private LayeredContextDecisionService service() {
        return new LayeredContextDecisionService(intentUnderstandingService, new DecisionValidator(), 0.80D);
    }

    private LayeredContextDecisionService boundedService() {
        return new LayeredContextDecisionService(intentUnderstandingService, new DecisionValidator(),
                new ContextModelCallPolicy(3_000, 8_000, 4_000, 15_000, 0), 0.80D);
    }

    private TurnContext context(List<ContextCandidate> candidates) {
        return TurnContext.start("turn:24", "web", "customer-1", 24L, 100L,
                "有没有视频的？", candidates);
    }

    private ContextCandidate candidate(String id, String source, String content) {
        return new ContextCandidate(id, source, content, 24L, 9L,
                "web", "customer-1", 1D, null, null, "test");
    }

    private ContextDecision decision(ContextDecision.Relation relation, List<String> selectedContextIds,
                                     List<String> selectedMemoryIds, List<String> requirements,
                                     String resolvedQuery, double confidence, boolean needLargeModel) {
        return new ContextDecision(relation, "PRODUCT_USAGE", selectedContextIds, selectedMemoryIds,
                ContextDecision.TaskAction.CONTINUE, "task:usage", requirements, resolvedQuery,
                confidence, needLargeModel);
    }
}
