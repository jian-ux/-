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
               COALESCE(NULLIF(customer.name, ''), NULLIF(customer.nickname, ''),
                        NULLIF(channel_user.nickname, '')) AS customer_name
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
        ORDER BY FIELD(c.status, 'transferred', 'active', 'closed') ASC,
                 FIELD(c.priority, 'P0', 'P1', 'P2', 'P3') ASC,
                 c.update_time DESC
        </script>
        """)
    Page<BotConversation> selectMonitorPage(
        Page<BotConversation> page,
        @Param("status") String status,
        @Param("emotionLabel") String emotionLabel,
        @Param("emotionRisk") String emotionRisk);

    @Select(MONITOR_SELECT + " WHERE c.id = #{id} AND c.deleted = 0")
    BotConversation selectMonitorById(@Param("id") Long id);
}
