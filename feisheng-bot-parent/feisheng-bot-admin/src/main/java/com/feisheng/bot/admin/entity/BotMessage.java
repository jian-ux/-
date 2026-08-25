package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("bot_message")
public class BotMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String role;
    private String contentType;
    private String content;
    private String metadata;
    @TableField(exist = false)
    private String mediaUrl;
    private Date createTime;
}
