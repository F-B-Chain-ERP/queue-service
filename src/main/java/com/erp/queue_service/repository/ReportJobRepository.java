package com.erp.queue_service.repository;

import com.erp.core.domain.ReportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

/**
 * Repository cho ReportJob trong queue-service để cập nhật tiến độ và trạng thái hoàn thành.
 */
@Repository
public interface ReportJobRepository extends JpaRepository<ReportJob, UUID> {

    /**
     * Conditional claim: nhận job PENDING hoặc reclaim một job PROCESSING khi RabbitMQ
     * xác nhận đây là message được redeliver sau khi delivery trước bị gián đoạn.
     */
    @Transactional
    @Modifying
    @Query("""
        UPDATE ReportJob j
        SET j.status = 'PROCESSING',
            j.startedAt = :now,
            j.heartbeatAt = :now,
            j.attemptCount = COALESCE(j.attemptCount, 0) + 1
        WHERE j.id = :jobId
          AND (j.status = 'PENDING'
               OR (:allowProcessingReclaim = true AND j.status = 'PROCESSING'))
    """)
    int claimJob(@Param("jobId") UUID jobId,
                 @Param("now") Instant now,
                 @Param("allowProcessingReclaim") boolean allowProcessingReclaim);

    @Transactional
    @Modifying
    @Query("""
        UPDATE ReportJob j
        SET j.status = 'DONE',
            j.fileUrl = :fileUrl,
            j.objectKey = :objectKey,
            j.completedAt = :completedAt,
            j.errorMessage = null
        WHERE j.id = :jobId
    """)
    int markDone(@Param("jobId") UUID jobId,
                 @Param("fileUrl") String fileUrl,
                 @Param("objectKey") String objectKey,
                 @Param("completedAt") Instant completedAt);

    @Transactional
    @Modifying
    @Query("""
        UPDATE ReportJob j
        SET j.status = 'FAILED',
            j.errorMessage = :errorMessage,
            j.lastError = :errorMessage,
            j.completedAt = :completedAt
        WHERE j.id = :jobId
    """)
    int markFailed(@Param("jobId") UUID jobId,
                   @Param("errorMessage") String errorMessage,
                   @Param("completedAt") Instant completedAt);

    @Transactional
    @Modifying
    @Query("UPDATE ReportJob j SET j.heartbeatAt = :now WHERE j.id = :jobId")
    int updateHeartbeat(@Param("jobId") UUID jobId, @Param("now") Instant now);

    @Query("SELECT j FROM ReportJob j WHERE j.status = 'PROCESSING' AND (j.heartbeatAt < :cutoff OR (j.heartbeatAt IS NULL AND j.startedAt < :cutoff))")
    List<ReportJob> findStalledJobs(@Param("cutoff") Instant cutoff);

    @Query("SELECT j FROM ReportJob j WHERE j.status = 'DONE' AND j.completedAt < :cutoff")
    List<ReportJob> findExpiredJobs(@Param("cutoff") Instant cutoff);
}
