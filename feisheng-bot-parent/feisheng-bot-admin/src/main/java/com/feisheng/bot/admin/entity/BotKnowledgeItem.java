package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@TableName("bot_knowledge_item")
public class BotKnowledgeItem extends BaseEntity {
    private Long categoryId;
    private String question;
    private String answer;
    private String keywords;
    private String alternateQuestions;
    private Integer status;
    private Integer hitCount;
    private Integer directAnswerEnabled;
    @JsonIgnore
    private String embedding;
    @JsonIgnore
    private String embeddingModel;
    @JsonIgnore
    private String embeddingVersion;
    @JsonIgnore
    private Integer embeddingDimensions;
    @JsonIgnore
    private String embeddingContentHash;
    @TableField(exist = false)
    private Boolean embeddingReady;
}
