package com.feisheng.bot.core.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;
@Data
@TableName("bot_message")
public class BotMessage {
    @TableId(type = IdType.AUTO) private Long id;
    private Long conversationId; private String role; private String contentType;
    private String content; private String metadata;
    @TableField(exist = false) private String mediaUrl;
    private Date createTime;
}
