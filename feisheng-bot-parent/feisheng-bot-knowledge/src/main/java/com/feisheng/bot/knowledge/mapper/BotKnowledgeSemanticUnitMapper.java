package com.feisheng.bot.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

@Mapper
public interface BotKnowledgeSemanticUnitMapper extends BaseMapper<BotKnowledgeSemanticUnit> {
    @Select("SELECT su.* FROM bot_knowledge_semantic_unit su JOIN bot_knowledge_document d ON d.id=su.document_id WHERE su.status='APPROVED' AND su.deleted=0 AND d.publish_status='PUBLISHED' AND d.source_scope='KNOWLEDGE' AND d.deleted=0 AND (d.effective_from IS NULL OR d.effective_from <= CURRENT_TIMESTAMP) AND (d.effective_to IS NULL OR d.effective_to > CURRENT_TIMESTAMP) AND d.knowledge_set_key=#{knowledgeSetKey}")
    List<BotKnowledgeSemanticUnit> selectIndexableApprovedUnits(@Param("knowledgeSetKey") String knowledgeSetKey);

    @Update("""
        UPDATE bot_knowledge_semantic_unit
        SET status = #{targetStatus}, reviewed_by = #{reviewedBy},
            reviewed_at = #{reviewedAt}, review_reason = #{reviewReason}
        WHERE id = #{id} AND status = #{expectedStatus} AND deleted = 0
        """)
    int transitionReview(@Param("id") Long id,
                         @Param("expectedStatus") String expectedStatus,
                         @Param("targetStatus") String targetStatus,
                         @Param("reviewedBy") Long reviewedBy,
                         @Param("reviewedAt") Date reviewedAt,
                         @Param("reviewReason") String reviewReason);
}
