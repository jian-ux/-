package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("bot_unmatched_question")
public class BotUnmatchedQuestion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String question;
    private Integer similarCount;
    private Integer isResolved;
    private Date createTime;
}
