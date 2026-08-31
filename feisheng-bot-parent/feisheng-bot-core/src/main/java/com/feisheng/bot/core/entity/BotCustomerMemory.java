package com.feisheng.bot.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** A customer-scoped, explicitly admitted long-term fact. */
@Data
@TableName("bot_customer_memory")
public class BotCustomerMemory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long customerId;
    private String memoryKey;
    private String memoryValue;
    private String source;
    private Double confidence;
    private String status;
    private Long sourceMessageId;
    private Date createTime;
    private Date updatedAt;
    @TableLogic
    private Integer deleted;
}
