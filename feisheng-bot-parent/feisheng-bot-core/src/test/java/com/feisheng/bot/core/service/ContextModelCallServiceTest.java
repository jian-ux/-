package com.feisheng.bot.core.service;

import com.feisheng.bot.core.dto.ChatResponse;
import com.feisheng.bot.core.dto.LlmFailureType;
import com.feisheng.bot.core.service.impl.AiModelServiceImpl;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextModelCallServiceTest {

    @Mock
    private AiModelServiceImpl aiModelService;

    @Test
    void retriesSameModelOnlyAfterSchemaUnsupported() {
        when(aiModelService.chatWithExactModelJsonWithPolicy(anyString(), anyString(), eq(5L),
                anyMap(), eq(8_000), eq(0)))
                .thenReturn(failed(LlmFailureType.SCHEMA_UNSUPPORTED));
        when(aiModelService.chatWithExactModelWithPolicy(anyString(), anyString(), eq(5L),
                eq(8_000), eq(0)))
                .thenReturn(new ChatResponse("{}", true));

        ContextModelCallService.CallResult result = service().callJsonDecision(5L, "prompt",
                "schema-prompt", Map.of("type", "object"), ContextModelCallPolicy.Tier.DEEP,
                System.nanoTime() + 10_000_000_000L);

        assertTrue(result.schemaFallbackUsed());
        assertTrue(result.response().isSuccess());
        verify(aiModelService).chatWithExactModelWithPolicy("prompt", "schema-prompt", 5L,
                8_000, 0);
    }

    @Test
    void doesNotRepeatModelAfterTimeout() {
        when(aiModelService.chatWithExactModelJsonWithPolicy(anyString(), anyString(), eq(5L),
                anyMap(), eq(8_000), eq(0)))
                .thenReturn(failed(LlmFailureType.TIMEOUT));

        ContextModelCallService.CallResult result = service().callJsonDecision(5L, "prompt",
                "schema-prompt", Map.of("type", "object"), ContextModelCallPolicy.Tier.DEEP,
                System.nanoTime() + 10_000_000_000L);

        assertEquals(LlmFailureType.TIMEOUT, result.response().getFailureType());
        verify(aiModelService, never()).chatWithExactModelWithPolicy(anyString(), anyString(),
                eq(5L), eq(8_000), eq(0));
    }

    @Test
    void opensOnlyTheFailingModelsContextCircuit() {
        CircuitBreakerConfig contextConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(1)
                .minimumNumberOfCalls(1)
                .failureRateThreshold(1F)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(
                Map.of("contextModel", contextConfig));
        ContextModelCallService service = service(registry);
        when(aiModelService.chatWithExactModelJsonWithPolicy(anyString(), anyString(), eq(5L),
                anyMap(), eq(8_000), eq(0)))
                .thenReturn(failed(LlmFailureType.TIMEOUT));
        when(aiModelService.chatWithExactModelJsonWithPolicy(anyString(), anyString(), eq(6L),
                anyMap(), eq(8_000), eq(0)))
                .thenReturn(new ChatResponse("{}", true));

        ContextModelCallService.CallResult firstFailure = service.callJsonDecision(5L, "prompt",
                "schema-prompt", Map.of("type", "object"), ContextModelCallPolicy.Tier.DEEP,
                System.nanoTime() + 10_000_000_000L);
        ContextModelCallService.CallResult openCircuit = service.callJsonDecision(5L, "prompt",
                "schema-prompt", Map.of("type", "object"), ContextModelCallPolicy.Tier.DEEP,
                System.nanoTime() + 10_000_000_000L);
        ContextModelCallService.CallResult otherModel = service.callJsonDecision(6L, "prompt",
                "schema-prompt", Map.of("type", "object"), ContextModelCallPolicy.Tier.DEEP,
                System.nanoTime() + 10_000_000_000L);

        assertEquals(LlmFailureType.TIMEOUT, firstFailure.response().getFailureType());
        assertTrue(openCircuit.circuitOpen());
        assertEquals(LlmFailureType.CIRCUIT_OPEN, openCircuit.response().getFailureType());
        assertTrue(otherModel.response().isSuccess());
        assertFalse(otherModel.circuitOpen());
        verify(aiModelService, times(1)).chatWithExactModelJsonWithPolicy(anyString(), anyString(),
                eq(5L), anyMap(), eq(8_000), eq(0));
    }

    @Test
    void treatsNullProviderResponseAsModelUnavailableWithoutThrowing() {
        when(aiModelService.chatWithExactModelJsonWithPolicy(anyString(), anyString(), eq(5L),
                anyMap(), eq(8_000), eq(0)))
                .thenReturn(null);

        ContextModelCallService.CallResult result = service().callJsonDecision(5L, "prompt",
                "schema-prompt", Map.of("type", "object"), ContextModelCallPolicy.Tier.DEEP,
                System.nanoTime() + 10_000_000_000L);

        assertEquals(LlmFailureType.MODEL_UNAVAILABLE, result.response().getFailureType());
        assertFalse(result.response().isSuccess());
    }

    @Test
    void invalidDecisionOutcomeOpensOnlyThatModelsCircuit() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(1)
                .minimumNumberOfCalls(1)
                .failureRateThreshold(50F)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(
                Map.of("contextModel", config));
        ContextModelCallService service = service(registry);
        when(aiModelService.chatWithExactModelJsonWithPolicy(anyString(), anyString(), eq(6L),
                anyMap(), eq(8_000), eq(0)))
                .thenReturn(new ChatResponse("{}", true));

        service.recordDecisionOutcome(5L, 12L, LlmFailureType.INVALID_OUTPUT);
        ContextModelCallService.CallResult openCircuit = service.callJsonDecision(5L, "prompt",
                "schema-prompt", Map.of("type", "object"), ContextModelCallPolicy.Tier.DEEP,
                System.nanoTime() + 10_000_000_000L);
        ContextModelCallService.CallResult otherModel = service.callJsonDecision(6L, "prompt",
                "schema-prompt", Map.of("type", "object"), ContextModelCallPolicy.Tier.DEEP,
                System.nanoTime() + 10_000_000_000L);

        assertTrue(openCircuit.circuitOpen());
        assertEquals(LlmFailureType.CIRCUIT_OPEN, openCircuit.response().getFailureType());
        assertTrue(otherModel.response().isSuccess());
    }

    private ContextModelCallService service() {
        return service(CircuitBreakerRegistry.ofDefaults());
    }

    private ContextModelCallService service(CircuitBreakerRegistry registry) {
        return new ContextModelCallService(aiModelService,
                new ContextModelCallPolicy(3_000, 8_000, 4_000, 15_000, 0),
                registry);
    }

    private ChatResponse failed(LlmFailureType failureType) {
        ChatResponse response = new ChatResponse("failed", false);
        response.setFailureType(failureType);
        return response;
    }
}
