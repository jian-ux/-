package com.feisheng.bot.knowledge.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Date;

@Data
@TableName("bot_knowledge_document")
public class BotKnowledgeDocument {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String fileName;
    /** @deprecated use objectKey instead */
    @Deprecated
    private String filePath;
    private String bucketName;
    private String objectKey;
    private String fileType;
    private String mediaType;
    private String sourceScope;
    private String ocrStatus;
    @JsonIgnore
    private String ocrText;
    private String ocrLanguage;
    private String ocrError;
    private Date expiresAt;
    private Long fileSize;
    private Long categoryId;
    private Integer status;
    private Date createTime;
    @TableLogic
    private Integer deleted;
}
