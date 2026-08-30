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

    @Update("UPDATE bot_knowledge_migration_job SET status=#{targetStatus}, current_step=#{currentStep}, lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status=#{expectedStatus} AND lease_owner=#{workerId} AND lock_version=#{expectedLockVersion}")
    int transitionOwned(@Param("id") Long id, @Param("expectedStatus") String expectedStatus,
                        @Param("targetStatus") String targetStatus, @Param("currentStep") String currentStep,
                        @Param("workerId") String workerId, @Param("expectedLockVersion") long expectedLockVersion);

    @Update("UPDATE bot_knowledge_migration_job SET target_document_id=COALESCE(#{targetDocumentId}, target_document_id), total_units=#{totalUnits}, processed_units=#{processedUnits}, conflict_units=#{conflictUnits}, approved_units=#{approvedUnits}, error_message=#{errorMessage}, lease_until=#{leaseUntil}, lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND lease_owner=#{workerId} AND lock_version=#{expectedLockVersion}")
    int updateProgressOwned(@Param("id") Long id, @Param("workerId") String workerId,
                            @Param("expectedLockVersion") long expectedLockVersion,
                            @Param("targetDocumentId") Long targetDocumentId,
                            @Param("totalUnits") Integer totalUnits, @Param("processedUnits") Integer processedUnits,
                            @Param("conflictUnits") Integer conflictUnits, @Param("approvedUnits") Integer approvedUnits,
                            @Param("errorMessage") String errorMessage, @Param("leaseUntil") Date leaseUntil);

    @Update("UPDATE bot_knowledge_migration_job SET target_document_id=#{targetDocumentId}, lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND lease_owner=#{workerId} AND lock_version=#{expectedLockVersion}")
    int setTargetDocumentOwned(@Param("id") Long id, @Param("targetDocumentId") Long targetDocumentId,
                               @Param("workerId") String workerId, @Param("expectedLockVersion") long expectedLockVersion);

    @Update("UPDATE bot_knowledge_migration_job SET status=#{status}, current_step=#{currentStep}, error_message=#{errorMessage}, lease_owner=NULL, lease_until=NULL, lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND lease_owner=#{workerId} AND lock_version=#{expectedLockVersion}")
    int failOwned(@Param("id") Long id, @Param("workerId") String workerId,
                  @Param("expectedLockVersion") long expectedLockVersion, @Param("status") String status,
                  @Param("currentStep") String currentStep, @Param("errorMessage") String errorMessage);

    @Update("UPDATE bot_knowledge_migration_job SET status='FAILED', error_message=#{message}, lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status=#{expectedStatus} AND lock_version=#{expectedLockVersion}")
    int markQueueRejected(@Param("id") Long id, @Param("expectedStatus") String expectedStatus,
                          @Param("expectedLockVersion") long expectedLockVersion, @Param("message") String message);

    @Update("UPDATE bot_knowledge_migration_job SET lease_until=#{leaseUntil}, updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND lease_owner=#{workerId} AND lock_version=#{expectedLockVersion}")
    int renewLease(@Param("id") Long id, @Param("workerId") String workerId, @Param("leaseUntil") Date leaseUntil, @Param("expectedLockVersion") long expectedLockVersion);

    @Select("SELECT * FROM bot_knowledge_migration_job WHERE id=#{id} FOR UPDATE")
    BotKnowledgeMigrationJob findByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE bot_knowledge_migration_job SET status='READY_TO_SWITCH', current_step='READY_TO_SWITCH', reviewer_id=#{reviewerId}, reviewed_at=#{reviewedAt}, review_reason=#{reviewReason}, review_audit_json=#{reviewAuditJson}, lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='REVIEW_REQUIRED' AND lock_version=#{expectedLockVersion}")
    int confirm(@Param("id") Long id, @Param("expectedLockVersion") long expectedLockVersion,
                @Param("reviewerId") Long reviewerId, @Param("reviewedAt") Date reviewedAt,
                @Param("reviewReason") String reviewReason, @Param("reviewAuditJson") String reviewAuditJson);
}
