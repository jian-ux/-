package com.feisheng.bot.gateway.dto;

public record DingTalkMediaRequest(
        String msgId,
        String msgType,
        String downloadCode,
        String recognition,
        String fileName,
        String robotCode,
        String caption) {

    public DingTalkMediaRequest(String msgId, String msgType, String downloadCode,
                                String recognition, String fileName, String robotCode) {
        this(msgId, msgType, downloadCode, recognition, fileName, robotCode, null);
    }
}
