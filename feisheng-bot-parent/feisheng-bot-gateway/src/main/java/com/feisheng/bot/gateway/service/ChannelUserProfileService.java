package com.feisheng.bot.gateway.service;

import com.feisheng.bot.gateway.dto.ChannelMessageDTO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ChannelUserProfileService {
    private static final String UPSERT_CHANNEL_USER_SQL = """
        INSERT INTO bot_channel_user (channel_type, channel_user_id, nickname, avatar)
        VALUES (?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
            nickname = COALESCE(NULLIF(VALUES(nickname), ''), bot_channel_user.nickname),
            avatar = COALESCE(NULLIF(VALUES(avatar), ''), bot_channel_user.avatar)
        """;
    private static final String UPSERT_CUSTOMER_SQL = """
        INSERT INTO bot_customer (
            channel_type, channel_user_id, nickname, avatar,
            total_conversations, last_contact_time, deleted
        )
        VALUES (?, ?, ?, ?, 0, CURRENT_TIMESTAMP, 0)
        ON DUPLICATE KEY UPDATE
            nickname = COALESCE(NULLIF(VALUES(nickname), ''), bot_customer.nickname),
            avatar = COALESCE(NULLIF(VALUES(avatar), ''), bot_customer.avatar),
            last_contact_time = VALUES(last_contact_time),
            deleted = 0
        """;
    private static final String REFRESH_CONVERSATION_STATS_SQL = """
        UPDATE bot_customer customer
        SET customer.total_conversations = (
            SELECT COUNT(*)
            FROM bot_conversation conversation
            WHERE conversation.channel_type = customer.channel_type
              AND conversation.channel_user_id = customer.channel_user_id
              AND conversation.deleted = 0
        )
        WHERE customer.channel_type = ?
          AND customer.channel_user_id = ?
          AND customer.deleted = 0
        """;

    private final JdbcTemplate jdbcTemplate;

    public ChannelUserProfileService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(ChannelMessageDTO message) {
        if (message == null || !StringUtils.hasText(message.getChannelType())
                || !StringUtils.hasText(message.getChannelUserId())) {
            return;
        }
        String channelType = message.getChannelType().trim();
        String channelUserId = message.getChannelUserId().trim();
        String nickname = trimToNull(message.getSenderName());
        String avatar = trimToNull(message.getSenderAvatar());
        jdbcTemplate.update(UPSERT_CHANNEL_USER_SQL,
            channelType, channelUserId, nickname, avatar);
        jdbcTemplate.update(UPSERT_CUSTOMER_SQL,
            channelType, channelUserId, nickname, avatar);
    }

    public void refreshConversationStats(ChannelMessageDTO message) {
        if (message == null || !StringUtils.hasText(message.getChannelType())
                || !StringUtils.hasText(message.getChannelUserId())) {
            return;
        }
        jdbcTemplate.update(REFRESH_CONVERSATION_STATS_SQL,
            message.getChannelType().trim(), message.getChannelUserId().trim());
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
