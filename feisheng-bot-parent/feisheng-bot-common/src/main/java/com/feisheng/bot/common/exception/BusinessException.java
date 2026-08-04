package com.feisheng.bot.common.exception;
public class BusinessException extends RuntimeException {
    private final int code;
    public BusinessException(int code, String msg) { super(msg); this.code=code; }
    public BusinessException(String msg) { this(500, msg); }
    public int getCode() { return code; }
}