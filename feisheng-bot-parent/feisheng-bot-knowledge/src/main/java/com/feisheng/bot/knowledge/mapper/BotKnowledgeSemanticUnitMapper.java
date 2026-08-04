package com.feisheng.bot.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feisheng.bot.knowledge.entity.BotKnowledgeSemanticUnit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface BotKnowledgeSemanticUnitMapper extends BaseMapper<BotKnowledgeSemanticUnit> {
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
