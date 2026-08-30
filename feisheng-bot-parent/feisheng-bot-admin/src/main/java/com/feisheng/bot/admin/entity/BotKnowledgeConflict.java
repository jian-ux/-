package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("bot_knowledge_conflict")
public class BotKnowledgeConflict {
    @TableId(type = IdType.AUTO) private Long id;
    private Long migrationJobId;
    private Long targetUnitId;
    private Long candidateUnitId;
    private Double similarity;
    private String scopeRelation;
    private String conflictType;
    private String status;
    private String severity;
    private String evidence;
    private String ruleResult;
    private String llmResult;
    private String resolution;
    private String resolutionNote;
    private Long reviewerId;
    private Date reviewedAt;
    private Date createTime;
    private Date updatedAt;
}
