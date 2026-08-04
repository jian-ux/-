package com.feisheng.bot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("bot_knowledge_chunk")
public class BotKnowledgeChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private Integer chunkIndex;
    private String content;
    private String sectionPath;
    private Integer charCount;
    private String chunkStrategyVersion;
    private String contentType;
    private String qaQuestion;
    private String qaAnswer;
    private String qaKey;
    private String qaGroupKey;
    private Integer qaVersion;
    private Integer directAnswerEnabled;
    private String embedding;
    private String embeddingModel;
    private String embeddingVersion;
    private Integer embeddingDimensions;
    private String embeddingContentHash;
    private String status;
    private Date createTime;
    @TableLogic
    private Integer deleted;
}
