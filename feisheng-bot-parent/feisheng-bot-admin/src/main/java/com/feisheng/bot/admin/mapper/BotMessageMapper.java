package com.feisheng.bot.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.dto.CustomerTimelineItem;
import com.feisheng.bot.admin.entity.BotMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BotMessageMapper extends BaseMapper<BotMessage> {
    @Select("""
        SELECT m.id AS message_id,
               m.conversation_id AS conversation_id,
               c.title AS conversation_title,
               c.status AS conversation_status,
               m.role AS role,
               m.content_type AS content_type,
               m.content AS content,
               m.metadata AS metadata,
               m.create_time AS create_time
        FROM bot_message m
        INNER JOIN bot_conversation c ON c.id = m.conversation_id
        WHERE c.channel_type = #{channelType}
          AND c.channel_user_id = #{channelUserId}
          AND c.deleted = 0
        ORDER BY m.create_time ASC, m.id ASC
        """)
    Page<CustomerTimelineItem> selectCustomerTimeline(
        Page<CustomerTimelineItem> page,
        @Param("channelType") String channelType,
        @Param("channelUserId") String channelUserId);
}
