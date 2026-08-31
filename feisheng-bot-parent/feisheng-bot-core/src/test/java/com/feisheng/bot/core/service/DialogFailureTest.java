package com.feisheng.bot.core.service;

import com.feisheng.bot.core.dto.ChatResponse;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogFailureTest {

    @Test
    void classifiesTimeoutWithoutExposingCause() {
        DialogFailure failure = DialogFailure.from(new SocketTimeoutException(
            "api-key=secret timed out"));

        assertEquals(DialogErrorCode.MODEL_TIMEOUT, failure.code());
        assertFalse(failure.safeMessage().contains("secret"));
        assertTrue(failure.safeMessage().contains("暂时不可用"));
    }

    @Test
    void classifiesCircuitOpenAndQueueSaturation() {
        assertEquals(DialogErrorCode.MODEL_CIRCUIT_OPEN,
            DialogFailure.from(new IllegalStateException("CircuitBreaker aiModel is OPEN"))
                .code());
        assertEquals(DialogErrorCode.ASYNC_QUEUE_FULL,
            DialogFailure.from(new RejectedExecutionException("executor queue full"))
                .code());
    }

    @Test
    void classifiesUnsuccessfulModelResponse() {
        ChatResponse response = new ChatResponse("read timeout", false,
            "unavailable", "timeout", 0, 0);

        DialogFailure failure = DialogFailure.from(response);

        assertEquals(DialogErrorCode.MODEL_TIMEOUT, failure.code());
        assertEquals("AI服务暂时不可用", failure.safeMessage());
    }
}
