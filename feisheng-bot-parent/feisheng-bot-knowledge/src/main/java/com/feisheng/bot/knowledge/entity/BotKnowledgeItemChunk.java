package com.feisheng.bot.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("bot_knowledge_item_chunk")
public class BotKnowledgeItemChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long itemId;
    private Integer chunkIndex;
    private String content;
    private String embedding;
    private String embeddingModel;
    private String embeddingVersion;
    private Integer embeddingDimensions;
    private String embeddingContentHash;
    private Date createTime;
}
