package com.feisheng.bot.core.service;

import com.feisheng.bot.core.dto.ChatResponse;

import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/** User-safe failure classification used by the dialog response diagnostics. */
public record DialogFailure(DialogErrorCode code, String safeMessage) {

    public DialogFailure {
        code = code == null ? DialogErrorCode.INTERNAL_ERROR : code;
        safeMessage = code.defaultMessage();
    }

    public static DialogFailure from(Throwable failure) {
        if (failure == null) return new DialogFailure(DialogErrorCode.INTERNAL_ERROR, null);
        if (failure instanceof RejectedExecutionException
                || failure.getClass().getName().contains("RejectedExecution")) {
            return new DialogFailure(DialogErrorCode.ASYNC_QUEUE_FULL, null);
        }
        if (failure instanceof SocketTimeoutException || failure instanceof TimeoutException
                || contains(failure, "timeout", "timed out", "超时")) {
            return new DialogFailure(DialogErrorCode.MODEL_TIMEOUT, null);
        }
        if (failure.getClass().getName().contains("CallNotPermittedException")
                || contains(failure, "circuit", "熔断")
                && contains(failure, "open", "opened", "打开")) {
            return new DialogFailure(DialogErrorCode.MODEL_CIRCUIT_OPEN, null);
        }
        if (contains(failure, "retrieval", "检索", "knowledge", "知识库")) {
            return new DialogFailure(DialogErrorCode.RETRIEVAL_UNAVAILABLE, null);
        }
        if (failure instanceof IllegalArgumentException) {
            return new DialogFailure(DialogErrorCode.INPUT_INVALID, null);
        }
        return new DialogFailure(DialogErrorCode.INTERNAL_ERROR, null);
    }

    public static DialogFailure from(ChatResponse response) {
        if (response == null) return new DialogFailure(DialogErrorCode.INTERNAL_ERROR, null);
        if (response.isSuccess()) return null;
        String provider = response.getProviderCode();
        String content = response.getContent();
        if (contains(provider, "timeout", "timed out", "超时")
                || contains(content, "timeout", "timed out", "超时")) {
            return new DialogFailure(DialogErrorCode.MODEL_TIMEOUT, null);
        }
        if (contains(provider, "circuit", "熔断")
                || contains(content, "circuit", "熔断")) {
            return new DialogFailure(DialogErrorCode.MODEL_CIRCUIT_OPEN, null);
        }
        return new DialogFailure(DialogErrorCode.INTERNAL_ERROR, null);
    }

    private static boolean contains(Throwable failure, String... terms) {
        Throwable current = failure;
        while (current != null) {
            String text = (String.valueOf(current.getMessage()) + " "
                + current.getClass().getSimpleName()).toLowerCase(Locale.ROOT);
            for (String term : terms) {
                if (text.contains(term.toLowerCase(Locale.ROOT))) return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean contains(String value, String... terms) {
        if (value == null) return false;
        String text = value.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (text.contains(term.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
