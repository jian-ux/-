package com.feisheng.bot.gateway.service;

public class DingTalkMediaProcessingException extends RuntimeException {
    private final String userMessage;

    public DingTalkMediaProcessingException(String userMessage) {
        super(userMessage);
        this.userMessage = userMessage;
    }

    public DingTalkMediaProcessingException(String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.userMessage = userMessage;
    }

    public String getUserMessage() {
        return userMessage;
    }
}
