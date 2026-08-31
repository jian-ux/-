package com.feisheng.bot.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feisheng.bot.core.entity.BotMemoryOutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface BotMemoryOutboxEventMapper extends BaseMapper<BotMemoryOutboxEvent> {
    @Select("SELECT * FROM bot_memory_outbox_event "
        + "WHERE status = 'PENDING' AND available_at <= NOW() "
        + "AND (locked_until IS NULL OR locked_until < NOW()) "
        + "ORDER BY id LIMIT #{limit}")
    List<BotMemoryOutboxEvent> selectAvailable(@Param("limit") int limit);

    @Update("UPDATE bot_memory_outbox_event SET status='PROCESSING', "
        + "locked_until=DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND), update_time=NOW() "
        + "WHERE id=#{id} AND status='PENDING' "
        + "AND (locked_until IS NULL OR locked_until < NOW())")
    int claim(@Param("id") Long id, @Param("leaseSeconds") int leaseSeconds);
}
