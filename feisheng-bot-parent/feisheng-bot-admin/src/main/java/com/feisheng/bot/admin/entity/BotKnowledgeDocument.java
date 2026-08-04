package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
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

    @TableField(exist = false)
    private Integer chunkCount;

    @TableField(exist = false)
    private Integer embeddingCount;

    @TableField(exist = false)
    private Integer approvedCount;
}
