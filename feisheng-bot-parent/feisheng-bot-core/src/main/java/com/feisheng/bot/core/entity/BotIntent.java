package com.feisheng.bot.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("bot_intent")
public class BotIntent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String intentName;
    private String intentKeywords;
    private String replyTemplate;
    private Integer status;
    @TableLogic
    private Integer deleted;
}
