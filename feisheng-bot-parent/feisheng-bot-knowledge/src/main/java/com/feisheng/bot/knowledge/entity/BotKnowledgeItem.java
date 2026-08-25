package com.feisheng.bot.knowledge.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;
@Data
@TableName("bot_knowledge_item")
public class BotKnowledgeItem {
    @TableId(type = IdType.AUTO) private Long id;
    private Long categoryId;
    private String question;
    private String answer;
    private String keywords;
    private String alternateQuestions;
    private String embedding;
    private String embeddingModel;
    private String embeddingVersion;
    private Integer embeddingDimensions;
    private String embeddingContentHash;
    private Integer status;
    private Integer hitCount;
    private Integer directAnswerEnabled;
    private Date createTime;
    private Date updateTime;
    @TableLogic private Integer deleted;
}
