package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

@EqualsAndHashCode(callSuper = true)
@Data
@TableName("bot_faq_draft")
public class BotFaqDraft extends BaseEntity {
    private Long runId;
    private Long clusterId;
    private String question;
    private String answer;
    private String keywords;
    private String similarQuestionsJson;
    private String evidenceJson;
    private String evidenceStatus;
    private String generationMessage;
    private String generatorModel;
    private Long duplicateItemId;
    private Double duplicateScore;
    private String status;
    private Long createdBy;
    private Long reviewedBy;
    private Date reviewedAt;
    private String reviewReason;
    private Long publishedItemId;
    private Date publishedAt;
}
