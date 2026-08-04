package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("bot_daily_statistics")
public class BotDailyStatistics {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Date statDate;
    private Integer conversationCount;
    private Integer messageCount;
    private Integer faqHitCount;
    private Integer aiReplyCount;
    private Integer transferCount;
    private Date createTime;
}
