package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@TableName("bot_faq_regression_run")
public class BotFaqRegressionRun extends BaseEntity {
    private Integer passed;
    private String promptVersion;
    private Integer publishedDraftCount;
    private Integer datasetCaseCount;
    private Integer executedCaseCount;
    private Integer passedCaseCount;
    private Integer failedCaseCount;
    private Integer truncated;
    private String failedCasesJson;
}
