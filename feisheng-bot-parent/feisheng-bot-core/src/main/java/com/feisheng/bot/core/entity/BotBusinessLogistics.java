package com.feisheng.bot.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("bot_business_logistics")
public class BotBusinessLogistics {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private String carrier;
    private String trackingNo;
    private String status;
    private String latestEvent;
    private Date latestEventTime;
    private Date estimatedDeliveryTime;
    private Date createTime;
    private Date updateTime;
    @TableLogic
    private Integer deleted;
}
