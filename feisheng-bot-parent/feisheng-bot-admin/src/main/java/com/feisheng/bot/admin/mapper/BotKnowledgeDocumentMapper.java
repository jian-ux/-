package com.feisheng.bot.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feisheng.bot.admin.entity.BotKnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

@Mapper
public interface BotKnowledgeDocumentMapper extends BaseMapper<BotKnowledgeDocument> {
    @Select("SELECT * FROM bot_knowledge_document WHERE knowledge_set_key=#{key} AND publish_status='PUBLISHED' AND source_scope='KNOWLEDGE' AND deleted=0 ORDER BY document_version DESC, id DESC")
    List<BotKnowledgeDocument> selectPublishedByKnowledgeSetKey(@Param("key") String key);

    @Select("SELECT COUNT(*) FROM bot_knowledge_document WHERE ((bucket_name=#{bucketName}) OR (bucket_name IS NULL AND #{bucketName} IS NULL)) AND object_key=#{objectKey} AND deleted=0")
    int countActiveObjectReferences(@Param("bucketName") String bucketName, @Param("objectKey") String objectKey);

    @Update("UPDATE bot_knowledge_document SET publish_status='PUBLISHED', published_at=#{publishedAt}, effective_from=#{effectiveFrom}, effective_to=NULL WHERE id=#{targetId} AND publish_status='DRAFT' AND deleted=0")
    int publishDraftGuarded(@Param("targetId") Long targetId, @Param("publishedAt") Date publishedAt, @Param("effectiveFrom") Date effectiveFrom);

    @Update("UPDATE bot_knowledge_document SET publish_status='ARCHIVED', effective_to=#{effectiveTo} WHERE id=#{oldId} AND publish_status='PUBLISHED' AND deleted=0")
    int archivePublishedGuarded(@Param("oldId") Long oldId, @Param("effectiveTo") Date effectiveTo);
}
