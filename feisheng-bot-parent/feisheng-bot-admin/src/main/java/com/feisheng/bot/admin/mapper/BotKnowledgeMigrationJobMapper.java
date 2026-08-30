package com.feisheng.bot.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feisheng.bot.admin.entity.BotKnowledgeMigrationJob;
import org.apache.ibatis.annotations.*;
import java.util.Date;

@Mapper
public interface BotKnowledgeMigrationJobMapper extends BaseMapper<BotKnowledgeMigrationJob> {
    @Update("UPDATE bot_knowledge_migration_job SET status='RUNNING', lease_owner=#{workerId}, lease_until=#{leaseUntil}, lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status=#{expectedStatus} AND lock_version=#{expectedLockVersion} AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)")
    int claim(@Param("id") Long id, @Param("expectedStatus") String expectedStatus, @Param("workerId") String workerId, @Param("leaseUntil") Date leaseUntil, @Param("expectedLockVersion") long expectedLockVersion);

    @Update("UPDATE bot_knowledge_migration_job SET status=#{targetStatus}, current_step=#{currentStep}, lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status=#{expectedStatus} AND lock_version=#{expectedLockVersion}")
    int transition(@Param("id") Long id, @Param("expectedStatus") String expectedStatus, @Param("targetStatus") String targetStatus, @Param("currentStep") String currentStep, @Param("expectedLockVersion") long expectedLockVersion);

    @Select("SELECT * FROM bot_knowledge_migration_job WHERE id=#{id} FOR UPDATE")
    BotKnowledgeMigrationJob findByIdForUpdate(@Param("id") Long id);
}
