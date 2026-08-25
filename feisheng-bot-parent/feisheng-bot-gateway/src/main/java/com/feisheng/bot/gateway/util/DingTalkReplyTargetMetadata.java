package com.feisheng.bot.gateway.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.gateway.service.DingTalkImageReplyDispatcher.ReplyTarget;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists the DingTalk delivery target alongside the inbound user message.
 *
 * A conversation is keyed by the sender in the existing data model, while a
 * DingTalk group reply must use the group's openConversationId. Keeping the
 * target in message metadata lets human replies preserve the original
 * callback target without changing the conversation table schema.
 */
public final class DingTalkReplyTargetMetadata {
    public static final String CONVERSATION_ID = "dingtalkConversationId";
    public static final String CONVERSATION_TYPE = "dingtalkConversationType";
    public static final String SENDER_STAFF_ID = "dingtalkSenderStaffId";
    public static final String ROBOT_CODE = "dingtalkRobotCode";

    private DingTalkReplyTargetMetadata() {}

    public static String merge(ObjectMapper objectMapper, String existing,
                               ReplyTarget target) {
        if (target == null) return existing;
        Map<String, Object> metadata = read(objectMapper, existing);
        put(metadata, CONVERSATION_ID, target.conversationId());
        put(metadata, CONVERSATION_TYPE, target.conversationType());
        put(metadata, SENDER_STAFF_ID, target.senderStaffId());
        put(metadata, ROBOT_CODE, target.robotCode());
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return existing;
        }
    }

    public static ReplyTarget readTarget(ObjectMapper objectMapper, String metadata) {
        Map<String, Object> values = read(objectMapper, metadata);
        return new ReplyTarget(
            text(values.get(SENDER_STAFF_ID)),
            text(values.get(CONVERSATION_ID)),
            text(values.get(CONVERSATION_TYPE)),
            text(values.get(ROBOT_CODE)));
    }

    public static boolean hasTarget(ReplyTarget target) {
        return target != null && (hasText(target.senderStaffId())
            || hasText(target.conversationId()) || hasText(target.conversationType())
            || hasText(target.robotCode()));
    }

    private static Map<String, Object> read(ObjectMapper objectMapper, String metadata) {
        if (metadata == null || metadata.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> value = objectMapper.readValue(metadata,
                new TypeReference<>() {});
            return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static void put(Map<String, Object> metadata, String key, String value) {
        if (hasText(value)) metadata.put(key, value.trim());
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
