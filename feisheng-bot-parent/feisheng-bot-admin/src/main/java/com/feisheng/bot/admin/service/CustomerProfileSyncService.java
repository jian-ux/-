package com.feisheng.bot.admin.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerProfileSyncService {
    private static final String SYNC_CHANNEL_USERS_SQL = """
        INSERT INTO bot_customer (
            channel_type, channel_user_id, nickname, avatar,
            total_conversations, last_contact_time, create_time, deleted
        )
        SELECT channel_user.channel_type,
               channel_user.channel_user_id,
               channel_user.nickname,
               channel_user.avatar,
               COALESCE(conversation_stats.total_conversations, 0),
               COALESCE(conversation_stats.last_contact_time, channel_user.create_time),
               channel_user.create_time,
               0
        FROM bot_channel_user channel_user
        LEFT JOIN (
            SELECT channel_type, channel_user_id,
                   COUNT(*) AS total_conversations,
                   MAX(update_time) AS last_contact_time
            FROM bot_conversation
            WHERE deleted = 0
            GROUP BY channel_type, channel_user_id
        ) conversation_stats
          ON conversation_stats.channel_type = channel_user.channel_type
         AND conversation_stats.channel_user_id = channel_user.channel_user_id
        WHERE channel_user.channel_type IN ('dingtalk', 'wechat')
          AND channel_user.channel_user_id IS NOT NULL
          AND channel_user.channel_user_id != ''
        ON DUPLICATE KEY UPDATE
          nickname = COALESCE(NULLIF(VALUES(nickname), ''), bot_customer.nickname),
          avatar = COALESCE(NULLIF(VALUES(avatar), ''), bot_customer.avatar),
          total_conversations = VALUES(total_conversations),
          last_contact_time = VALUES(last_contact_time),
          deleted = 0
        """;

    private static final String SYNC_CONVERSATION_USERS_SQL = """
        INSERT INTO bot_customer (
            channel_type, channel_user_id, nickname, avatar,
            total_conversations, last_contact_time, create_time, deleted
        )
        SELECT conversation.channel_type,
               conversation.channel_user_id,
               MAX(channel_user.nickname),
               MAX(channel_user.avatar),
               COUNT(*),
               MAX(conversation.update_time),
               MIN(conversation.create_time),
               0
        FROM bot_conversation conversation
        LEFT JOIN bot_channel_user channel_user
          ON channel_user.channel_type = conversation.channel_type
         AND channel_user.channel_user_id = conversation.channel_user_id
        WHERE conversation.deleted = 0
          AND conversation.channel_type IN ('dingtalk', 'wechat')
          AND conversation.channel_user_id IS NOT NULL
          AND conversation.channel_user_id != ''
        GROUP BY conversation.channel_type, conversation.channel_user_id
        ON DUPLICATE KEY UPDATE
          nickname = COALESCE(NULLIF(VALUES(nickname), ''), bot_customer.nickname),
          avatar = COALESCE(NULLIF(VALUES(avatar), ''), bot_customer.avatar),
          total_conversations = VALUES(total_conversations),
          last_contact_time = VALUES(last_contact_time),
          deleted = 0
        """;

    private final JdbcTemplate jdbcTemplate;

    public CustomerProfileSyncService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public SyncResult sync() {
        int channelProfiles = jdbcTemplate.update(SYNC_CHANNEL_USERS_SQL);
        int conversationProfiles = jdbcTemplate.update(SYNC_CONVERSATION_USERS_SQL);
        return new SyncResult(channelProfiles, conversationProfiles,
            channelProfiles + conversationProfiles);
    }

    public record SyncResult(int channelProfiles, int conversationProfiles, int affectedRows) {}
}
