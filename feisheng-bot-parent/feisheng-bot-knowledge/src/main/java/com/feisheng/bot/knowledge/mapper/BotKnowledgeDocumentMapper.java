package com.feisheng.bot.knowledge.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feisheng.bot.knowledge.entity.BotKnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BotKnowledgeDocumentMapper extends BaseMapper<BotKnowledgeDocument> {
    @Select("""
        SELECT * FROM bot_knowledge_document
        WHERE title = #{title}
          AND media_type = 'IMAGE'
          AND source_scope = 'KNOWLEDGE'
          AND status = 2
          AND deleted = 0
        ORDER BY id DESC
        LIMIT 1
        """)
    BotKnowledgeDocument selectLatestAvailableImageByTitle(@Param("title") String title);
}
