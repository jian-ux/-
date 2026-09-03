package com.feisheng.bot.core.service;

import com.feisheng.bot.core.dto.ChatResponse;
import com.feisheng.bot.core.dto.LlmFailureType;
import com.feisheng.bot.core.service.impl.AiModelServiceImpl;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Isolates context-decision calls from the answer-generation model policy. */
@Service
public class ContextModelCallService {
    private final AiModelServiceImpl aiModelService;
    private final ContextModelCallPolicy policy;
    private final CircuitBreakerRegistry circuitBreakers;

    public ContextModelCallService(AiModelServiceImpl aiModelService,
                                   ContextModelCallPolicy policy,
                                   CircuitBreakerRegistry circuitBreakers) {
        this.aiModelService = aiModelService;
        this.policy = policy;
        this.circuitBreakers = circuitBreakers;
    }

    public CallResult callJsonDecision(Long modelId, String prompt, String schemaPrompt,
                                       Map<String, Object> schema,
                                       ContextModelCallPolicy.Tier tier,
                                       long deadlineNanos) {
        long started = System.nanoTime();
        int timeoutMs = policy.requestTimeoutMs(tier, deadlineNanos);
        if (modelId == null || modelId <= 0) {
            return result(unavailable(), started, false, false);
        }
        if (timeoutMs <= 0) {
            return result(failure("context turn deadline exceeded", LlmFailureType.TIMEOUT),
                    started, false, false);
        }

        String breakerName = "context-model-" + modelId;
        CircuitBreaker breaker = circuitBreakers.getConfiguration("contextModel")
                .map(config -> circuitBreakers.circuitBreaker(breakerName, config))
                .orElseGet(() -> circuitBreakers.circuitBreaker(breakerName));
        if (!breaker.tryAcquirePermission()) {
            return result(failure("context model circuit is open", LlmFailureType.CIRCUIT_OPEN),
                    started, false, true);
        }

        ChatResponse response;
        boolean schemaFallbackUsed = false;
        try {
            response = aiModelService.chatWithExactModelJsonWithPolicy(
                    prompt, schemaPrompt, modelId, schema, timeoutMs, policy.maxRetries());
            if (response != null
                    && response.getFailureType() == LlmFailureType.SCHEMA_UNSUPPORTED) {
                schemaFallbackUsed = true;
                int fallbackTimeoutMs = policy.requestTimeoutMs(tier, deadlineNanos);
                if (fallbackTimeoutMs > 0) {
                    response = aiModelService.chatWithExactModelWithPolicy(
                            prompt, schemaPrompt, modelId, fallbackTimeoutMs, policy.maxRetries());
                } else {
                    response = failure("context turn deadline exceeded", LlmFailureType.TIMEOUT);
                }
            }
        } catch (Exception exception) {
            response = failure("context model call failed", LlmFailureType.MODEL_UNAVAILABLE);
        }

        LlmFailureType failureType = response == null
                ? LlmFailureType.MODEL_UNAVAILABLE : response.getFailureType();
        if (response != null && response.isSuccess()) {
            breaker.onSuccess(Math.max(1L, System.nanoTime() - started), TimeUnit.NANOSECONDS);
        } else if (failureType != LlmFailureType.SCHEMA_UNSUPPORTED) {
            breaker.onError(Math.max(1L, System.nanoTime() - started), TimeUnit.NANOSECONDS,
                    new ContextModelException(failureType));
        }
        return result(response == null ? failure("context model unavailable",
                LlmFailureType.MODEL_UNAVAILABLE) : response, started, schemaFallbackUsed, false);
    }

    private CallResult result(ChatResponse response, long started, boolean schemaFallbackUsed,
                              boolean circuitOpen) {
        return new CallResult(response, elapsedMillis(started), schemaFallbackUsed, circuitOpen);
    }

    private ChatResponse unavailable() {
        return failure("context model is not configured", LlmFailureType.MODEL_UNAVAILABLE);
    }

    private ChatResponse failure(String message, LlmFailureType failureType) {
        ChatResponse response = new ChatResponse(message, false);
        response.setFailureType(failureType);
        return response;
    }

    private long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static final class ContextModelException extends RuntimeException {
        private ContextModelException(LlmFailureType failureType) {
            super(failureType == null ? LlmFailureType.MODEL_UNAVAILABLE.name() : failureType.name());
        }
    }

    public record CallResult(ChatResponse response, long latencyMs, boolean schemaFallbackUsed,
                             boolean circuitOpen) {
        public CallResult {
            latencyMs = Math.max(0L, latencyMs);
            response = response == null ? new ChatResponse("context model unavailable", false) : response;
        }
    }
}
