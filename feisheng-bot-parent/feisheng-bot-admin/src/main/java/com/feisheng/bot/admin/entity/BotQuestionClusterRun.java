package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** A persisted snapshot of one unmatched-question clustering analysis. */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("bot_question_cluster_run")
public class BotQuestionClusterRun extends BaseEntity {
    private Integer includeResolved;
    private Integer questionCount;
    private Integer clusterCount;
    private Integer noiseCount;
    private Double threshold;
    private Integer embeddingUsed;
    private String embeddingModel;
    private String embeddingVersion;
}
