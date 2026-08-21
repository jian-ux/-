package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@TableName("bot_question_cluster")
public class BotQuestionCluster extends BaseEntity {
    private Long runId;
    private Integer clusterNumber;
    private String title;
    private Integer questionCount;
    private Integer totalOccurrences;
    private Double cohesion;
    private Integer ignored;
    private Long mergedIntoId;
}
