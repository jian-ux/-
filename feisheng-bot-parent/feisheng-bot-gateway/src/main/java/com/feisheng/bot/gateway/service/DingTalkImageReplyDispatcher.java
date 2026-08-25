package com.feisheng.bot.gateway.service;

import java.util.Map;

public interface DingTalkImageReplyDispatcher {
    void dispatch(Map<String, Object> result, ReplyTarget target);

    record ReplyTarget(String senderStaffId, String conversationId,
                       String conversationType, String robotCode) {
        public boolean isGroup() {
            if (conversationType == null) return false;
            String value = conversationType.trim();
            return "2".equals(value) || "group".equalsIgnoreCase(value)
                || "groupchat".equalsIgnoreCase(value);
        }
    }
}
