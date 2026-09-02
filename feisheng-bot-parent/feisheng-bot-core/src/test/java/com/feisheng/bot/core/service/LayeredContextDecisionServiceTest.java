package com.feisheng.bot.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        when(intentUnderstandingService.decideContext(context, 11L))
                .thenReturn(IntentUnderstandingService.ContextModelResult.success(fast, 10L));

        LayeredContextDecisionService.DecisionResult result = service().decide(context, 11L, 22L);

        assertEquals(LayeredContextDecisionService.Route.FAST_MODEL, result.route());
        assertEquals("有没有视频的？", context.originalQuery());
        assertEquals(List.of("需要视频形式的教程"), result.decision().originalRequirements());
        assertEquals("点签是否提供使用视频教程？", result.decision().resolvedQuery());
        verify(intentUnderstandingService).decideContext(context, 11L);
        verifyNoMoreInteractions(intentUnderstandingService);
    }

    @Test
    void escalatesLowConfidenceFastDecisionToDeepModel() {
        TurnContext context = context(List.of(candidate("message:9", "recent_message", "点签的使用教程")));
        ContextDecision fast = decision(ContextDecision.Relation.FOLLOW_UP, List.of("message:9"),
                List.of(), List.of("视频教程"), "点签的视频教程", 0.55, true);
        ContextDecision deep = decision(ContextDecision.Relation.FOLLOW_UP, List.of("message:9"),
                List.of(), List.of("需要视频形式的教程"), "点签是否提供使用视频教程？", 0.91, false);
        when(intentUnderstandingService.decideContext(context, 11L))
                .thenReturn(IntentUnderstandingService.ContextModelResult.success(fast, 8L));
        when(intentUnderstandingService.decideContext(context, 22L))
                .thenReturn(IntentUnderstandingService.ContextModelResult.success(deep, 20L));

        LayeredContextDecisionService.DecisionResult result = service().decide(context, 11L, 22L);

        assertEquals(LayeredContextDecisionService.Route.DEEP_MODEL, result.route());
        assertEquals(deep, result.decision());
    }

    @Test
    void escalatesMultiIntentEvenWhenFastConfidenceIsHigh() {
        TurnContext context = context(List.of(candidate("message:9", "recent_message", "点签的使用教程")));
        ContextDecision fast = decision(ContextDecision.Relation.MULTI_INTENT, List.of("message:9"),
                List.of(), List.of("视频教程", "价格"), "点签视频教程和价格", 0.96, false);
        ContextDecision deep = decision(ContextDecision.Relation.MULTI_INTENT, List.of("message:9"),
                List.of(), List.of("视频教程", "价格"), "点签是否有视频教程，价格如何？", 0.90, false);
        when(intentUnderstandingService.decideContext(context, 11L))
                .thenReturn(IntentUnderstandingService.ContextModelResult.success(fast, 8L));
        when(intentUnderstandingService.decideContext(context, 22L))
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
        when(intentUnderstandingService.decideContext(context, 11L))
                .thenReturn(IntentUnderstandingService.ContextModelResult.success(invalid, 8L));
        when(intentUnderstandingService.decideContext(context, 22L))
                .thenReturn(IntentUnderstandingService.ContextModelResult.failed("model_unavailable", 20L));

        LayeredContextDecisionService.DecisionResult result = service().decide(context, 11L, 22L);

        assertEquals(LayeredContextDecisionService.Route.FALLBACK, result.route());
        assertEquals("有没有视频的？", result.decision().resolvedQuery());
        assertEquals(List.of(), result.decision().selectedContextIds());
        assertEquals("deep_model_unavailable", result.fallbackReason());
    }

    private LayeredContextDecisionService service() {
        return new LayeredContextDecisionService(intentUnderstandingService, new DecisionValidator(), 0.80D);
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
