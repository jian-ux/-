package com.feisheng.bot.core.dto;
public class ChatResponse {
    private String content;
    private boolean success;
    private String model;
    private String providerCode;
    private int inputTokens;
    private int outputTokens;
    private LlmFailureType failureType = LlmFailureType.NONE;

    public ChatResponse() {}

    public ChatResponse(String content, boolean success) {
        this.content = content; this.success = success;
    }

    public ChatResponse(String content, boolean success, String model, String providerCode, int inputTokens, int outputTokens) {
        this.content = content; this.success = success;
        this.model = model; this.providerCode = providerCode;
        this.inputTokens = inputTokens; this.outputTokens = outputTokens;
    }

    public String getContent() { return content; }
    public void setContent(String c) { content = c; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean s) { success = s; }
    public String getModel() { return model; }
    public void setModel(String m) { model = m; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String p) { providerCode = p; }
    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int t) { inputTokens = t; }
    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int t) { outputTokens = t; }
    public LlmFailureType getFailureType() { return failureType; }
    public void setFailureType(LlmFailureType type) {
        failureType = type == null ? LlmFailureType.NONE : type;
    }
}
