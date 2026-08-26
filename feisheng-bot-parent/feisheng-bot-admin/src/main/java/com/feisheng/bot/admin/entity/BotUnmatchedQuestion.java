package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;
import java.util.List;

@Data
@TableName("bot_unmatched_question")
public class BotUnmatchedQuestion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String question;
    private Integer similarCount;
    private Integer isResolved;
    private String triggerTypes;
    private Long conversationId;
    private String lastAnswerStatus;
    private String lastAnswerDecision;
    private String lastReasonCode;
    private String lastSource;
    private Double lastConfidence;
    private Integer lastLatencyMs;
    private Integer lastCsatScore;
    private String reviewStatus;
    private String reviewDecision;
    private Integer reviewCorrect;
    private String reviewCategory;
    private String reviewNote;
    private Long reviewedBy;
    private Date reviewedAt;
    private Date createTime;
    private Date updateTime;

    @TableField(exist = false)
    private List<ImprovementAdvice> improvementAdvice;

    public record ImprovementAdvice(String triggerType, String title,
                                    String suggestion, String recommendedAction) {}
}
