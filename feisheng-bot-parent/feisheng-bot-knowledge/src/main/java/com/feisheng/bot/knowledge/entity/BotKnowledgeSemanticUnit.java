package com.feisheng.bot.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("bot_knowledge_semantic_unit")
public class BotKnowledgeSemanticUnit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private Long categoryId;
    private String unitKey;
    private String unitType;
    private String question;
    private String statement;
    private String intent;
    private String entitiesJson;
    private String conditionsJson;
    private String exclusionsJson;
    private String queryVariantsJson;
    private String evidenceChunkIdsJson;
    private String sourceSpansJson;
    private String metadataJson;
    private Double extractionConfidence;
    private String extractorModel;
    private String promptVersion;
    private String schemaVersion;
    private String sourceHash;
    private String embedding;
    private String embeddingModel;
    private String embeddingVersion;
    private Integer embeddingDimensions;
    private String embeddingContentHash;
    private String status;
    private Long reviewedBy;
    private Date reviewedAt;
    private String reviewReason;
    private Date createTime;
    private Date updateTime;
    @TableLogic
    private Integer deleted;
}
