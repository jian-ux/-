package com.feisheng.bot.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("bot_tool_execution_log")
public class BotToolExecutionLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String requestId;
    private String toolName;
    private String providerCode;
    private String status;
    private String inputJson;
    private String outputSummary;
    private Integer latencyMs;
    private Date createTime;
}
