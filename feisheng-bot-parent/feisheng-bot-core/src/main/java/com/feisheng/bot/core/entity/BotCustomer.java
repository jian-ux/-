package com.feisheng.bot.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

@Data
@TableName("bot_customer")
public class BotCustomer {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String channelType;
    private String channelUserId;
    private String profileJson;
    private Date profileUpdatedAt;
    private Date createTime;
    private Date updateTime;
    @TableLogic
    private Integer deleted;
}
