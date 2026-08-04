package com.feisheng.bot.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("bot_business_order")
public class BotBusinessOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private String channelType;
    private String channelUserId;
    private String status;
    private String paymentStatus;
    private String itemSummary;
    private Long amountCents;
    private String currency;
    private Date orderTime;
    private Date createTime;
    private Date updateTime;
    @TableLogic
    private Integer deleted;
}
