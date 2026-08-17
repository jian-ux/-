package com.feisheng.bot.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BotConversationMapper extends BaseMapper<BotConversation> {
    String MONITOR_SELECT = """
        SELECT c.*,
               COALESCE(
                   NULLIF((
                       SELECT channel.channel_name
                       FROM bot_channel_config channel
                       WHERE channel.channel_type = c.channel_type
                         AND channel.deleted = 0
                       ORDER BY channel.status DESC, channel.id DESC
                       LIMIT 1
                   ), ''),
                   c.channel_type
               ) AS channel_name,
               COALESCE(NULLIF(customer.name, ''), NULLIF(customer.nickname, ''),
                        NULLIF(channel_user.nickname, ''), c.channel_user_id) AS customer_name
        FROM bot_conversation c
        LEFT JOIN bot_customer customer
          ON customer.channel_type = c.channel_type
         AND customer.channel_user_id = c.channel_user_id
         AND customer.deleted = 0
        LEFT JOIN bot_channel_user channel_user
          ON channel_user.channel_type = c.channel_type
         AND channel_user.channel_user_id = c.channel_user_id
        """;

    @Select("""
        <script>
        """ + MONITOR_SELECT + """
        WHERE c.deleted = 0
        <if test="status != null and status != ''">AND c.status = #{status}</if>
        <if test="emotionLabel != null and emotionLabel != ''">
          AND c.emotion_label = #{emotionLabel}
        </if>
        <if test="emotionRisk != null and emotionRisk != ''">
          AND c.emotion_risk = #{emotionRisk}
        </if>
        <if test="channelType != null and channelType != ''">
          AND c.channel_type = #{channelType}
        </if>
        <if test="customerName != null and customerName != ''">
          AND (
            customer.name LIKE CONCAT('%', #{customerName}, '%')
            OR customer.nickname LIKE CONCAT('%', #{customerName}, '%')
            OR channel_user.nickname LIKE CONCAT('%', #{customerName}, '%')
            OR c.channel_user_id LIKE CONCAT('%', #{customerName}, '%')
          )
        </if>
        ORDER BY FIELD(c.status, 'transferred', 'active', 'closed') ASC,
                 FIELD(c.priority, 'P0', 'P1', 'P2', 'P3') ASC,
                 c.update_time DESC
        </script>
        """)
    Page<BotConversation> selectMonitorPage(
        Page<BotConversation> page,
        @Param("status") String status,
        @Param("emotionLabel") String emotionLabel,
        @Param("emotionRisk") String emotionRisk,
        @Param("channelType") String channelType,
        @Param("customerName") String customerName);

    @Select(MONITOR_SELECT + " WHERE c.id = #{id} AND c.deleted = 0")
    BotConversation selectMonitorById(@Param("id") Long id);
}
