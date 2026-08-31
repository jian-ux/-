package com.feisheng.bot.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** Durable, retryable event for customer-scoped memory maintenance. */
@Data
@TableName("bot_memory_outbox_event")
public class BotMemoryOutboxEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventType;
    private String dedupKey;
    private Long customerId;
    private Long conversationId;
    private Long sourceMessageId;
    private String payload;
    private String status;
    private Integer attempts;
    private Date availableAt;
    private Date lockedUntil;
    private String lastErrorCode;
    private String lastErrorMessage;
    private Date processedAt;
    private Date createTime;
    private Date updateTime;
}
