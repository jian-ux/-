package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("bot_ticket_record")
public class BotTicketRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ticketId;
    private Long operatorId;
    private String action;
    private String content;
    private Date createTime;
}
