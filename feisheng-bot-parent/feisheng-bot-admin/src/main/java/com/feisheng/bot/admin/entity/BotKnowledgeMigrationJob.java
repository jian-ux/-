package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("bot_knowledge_migration_job")
public class BotKnowledgeMigrationJob {
    @TableId(type = IdType.AUTO) private Long id;
    private Long sourceDocumentId;
    private Long sourceVersionId;
    private Long targetDocumentId;
    private Long targetVersionId;
    private String knowledgeSetKey;
    private String sourceContentHash;
    private String status;
    private String currentStep;
    private Integer totalUnits;
    private Integer processedUnits;
    private Integer conflictUnits;
    private Integer approvedUnits;
    private Integer retryCount;
    private Integer maxRetries;
    private Date nextRetryAt;
    private String leaseOwner;
    private Date leaseUntil;
    private Long lockVersion;
    private Long reviewerId;
    private Date reviewedAt;
    private String reviewReason;
    private String reviewAuditJson;
    private Date switchedAt;
    private String errorMessage;
    private Date createTime;
    private Date updatedAt;
}
