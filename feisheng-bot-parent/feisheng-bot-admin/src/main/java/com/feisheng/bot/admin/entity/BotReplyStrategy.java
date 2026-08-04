package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("bot_reply_strategy")
public class BotReplyStrategy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String strategyName;
    private Integer priority;
    private String ruleCondition;
    private String action;
    private Integer status;
    private Date createTime;
    @TableLogic
    private Integer deleted;
}
