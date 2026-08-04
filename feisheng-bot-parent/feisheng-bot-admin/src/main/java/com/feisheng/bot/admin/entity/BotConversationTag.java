package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("bot_conversation_tag")
public class BotConversationTag {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String tagName;
    private Date createTime;
}
