package com.feisheng.bot.core.dto;

/** Machine-readable result category for an LLM provider call. */
public enum LlmFailureType {
    NONE,
    TIMEOUT,
    RATE_LIMIT,
    SERVER_ERROR,
    SCHEMA_UNSUPPORTED,
    INVALID_OUTPUT,
    MODEL_UNAVAILABLE,
    CLIENT_ERROR,
    CIRCUIT_OPEN
}
