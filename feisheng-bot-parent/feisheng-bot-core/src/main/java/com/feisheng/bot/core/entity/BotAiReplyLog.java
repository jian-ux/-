package com.feisheng.bot.core.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;
@Data
@TableName("bot_ai_reply_log")
public class BotAiReplyLog {
    @TableId(type = IdType.AUTO) private Long id;
    private Long messageId;
    private String modelName;
    private String providerCode;
    private String prompt;
    private String reply;
    private Integer tokensInput;
    private Integer tokensOutput;
    private Integer costCents;
    private Integer latencyMs;
    private Integer success;
    private String purpose;
    private String callStatus;
    private String traceJson;
    private String citedChunkIds;
    private Boolean ragUsed;
    private Date createTime;
}
