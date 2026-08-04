-- Backfill customer profiles collected before bot_customer was wired to channel messages.
INSERT INTO bot_customer (
  channel_type,
  channel_user_id,
  nickname,
  avatar,
  total_conversations,
  last_contact_time,
  create_time,
  deleted
)
SELECT
  channel_user.channel_type,
  channel_user.channel_user_id,
  channel_user.nickname,
  channel_user.avatar,
  COALESCE(conversation_stats.total_conversations, 0),
  COALESCE(conversation_stats.last_contact_time, channel_user.create_time),
  channel_user.create_time,
  0
FROM bot_channel_user channel_user
LEFT JOIN (
  SELECT
    channel_type,
    channel_user_id,
    COUNT(*) AS total_conversations,
    MAX(update_time) AS last_contact_time
  FROM bot_conversation
  WHERE deleted = 0
  GROUP BY channel_type, channel_user_id
) conversation_stats
  ON conversation_stats.channel_type = channel_user.channel_type
 AND conversation_stats.channel_user_id = channel_user.channel_user_id
ON DUPLICATE KEY UPDATE
  nickname = COALESCE(NULLIF(VALUES(nickname), ''), bot_customer.nickname),
  avatar = COALESCE(NULLIF(VALUES(avatar), ''), bot_customer.avatar),
  total_conversations = VALUES(total_conversations),
  last_contact_time = VALUES(last_contact_time),
  deleted = 0;
