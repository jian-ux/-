package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@TableName("bot_question_cluster_item")
public class BotQuestionClusterItem extends BaseEntity {
    private Long clusterId;
    private Long unmatchedQuestionId;
    private String question;
    private String analysisQuestion;
    private Integer similarCount;
    private Double similarityToTitle;
}
