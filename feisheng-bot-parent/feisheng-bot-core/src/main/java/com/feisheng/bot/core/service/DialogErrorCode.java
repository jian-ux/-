package com.feisheng.bot.core.service;

/** Stable diagnostic codes for dialog failures. */
public enum DialogErrorCode {
    INPUT_INVALID("输入参数无效"),
    RETRIEVAL_UNAVAILABLE("知识检索暂时不可用"),
    MODEL_TIMEOUT("AI服务暂时不可用"),
    MODEL_CIRCUIT_OPEN("AI服务暂时不可用"),
    ASYNC_QUEUE_FULL("后台任务繁忙"),
    INTERNAL_ERROR("当前查询出现异常，请稍后重试");

    private final String defaultMessage;

    DialogErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
