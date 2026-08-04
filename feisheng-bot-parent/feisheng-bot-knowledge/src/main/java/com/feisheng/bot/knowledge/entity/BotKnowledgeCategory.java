package com.feisheng.bot.knowledge.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;
@Data
@TableName("bot_knowledge_category")
public class BotKnowledgeCategory {
    @TableId(type = IdType.AUTO) private Long id;
    private Long parentId;
    private String name;
    private Integer sort;
    private Integer status;
    private Date createTime;
    private Date updateTime;
    @TableLogic private Integer deleted;
}