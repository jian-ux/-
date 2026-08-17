package com.feisheng.bot.admin.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotTicket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
@Mapper
public interface BotTicketMapper extends BaseMapper<BotTicket> {
    @Select("""
        <script>
        SELECT ticket.*,
               conversation.channel_type,
               conversation.channel_user_id,
               COALESCE(
                   NULLIF((
                       SELECT channel.channel_name
                       FROM bot_channel_config channel
                       WHERE channel.channel_type = conversation.channel_type
                         AND channel.deleted = 0
                       ORDER BY channel.status DESC, channel.id DESC
                       LIMIT 1
                   ), ''),
                   conversation.channel_type
               ) AS channel_name,
               COALESCE(NULLIF(customer.name, ''), NULLIF(customer.nickname, ''),
                        NULLIF(channel_user.nickname, ''), conversation.channel_user_id)
                   AS customer_name
        FROM bot_ticket ticket
        LEFT JOIN bot_conversation conversation
          ON conversation.id = ticket.conversation_id
         AND conversation.deleted = 0
        LEFT JOIN bot_customer customer
          ON customer.channel_type = conversation.channel_type
         AND customer.channel_user_id = conversation.channel_user_id
         AND customer.deleted = 0
        LEFT JOIN bot_channel_user channel_user
          ON channel_user.channel_type = conversation.channel_type
         AND channel_user.channel_user_id = conversation.channel_user_id
        WHERE ticket.deleted = 0
        <if test="status != null and status != ''">
          AND ticket.status = #{status}
        </if>
        <if test="assigneeId != null">
          AND ticket.assignee_id = #{assigneeId}
        </if>
        <if test="channelType != null and channelType != ''">
          AND conversation.channel_type = #{channelType}
        </if>
        <if test="customerName != null and customerName != ''">
          AND (
            customer.name LIKE CONCAT('%', #{customerName}, '%')
            OR customer.nickname LIKE CONCAT('%', #{customerName}, '%')
            OR channel_user.nickname LIKE CONCAT('%', #{customerName}, '%')
            OR conversation.channel_user_id LIKE CONCAT('%', #{customerName}, '%')
          )
        </if>
        ORDER BY FIELD(ticket.status, 'pending', 'processing', 'resolved', 'closed'),
                 FIELD(ticket.priority, 'P0', 'P1', 'P2', 'P3'),
                 CASE WHEN ticket.sla_deadline IS NULL THEN 1 ELSE 0 END,
                 ticket.sla_deadline ASC,
                 ticket.create_time DESC
        </script>
        """)
    Page<BotTicket> selectAdminPage(
        Page<BotTicket> page,
        @Param("status") String status,
        @Param("assigneeId") Long assigneeId,
        @Param("channelType") String channelType,
        @Param("customerName") String customerName);
}
