package com.erp.queue_service.repository;

import com.erp.core.domain.ReportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository cho ReportJob trong queue-service để cập nhật tiến độ và trạng thái hoàn thành.
 */
@Repository
public interface ReportJobRepository extends JpaRepository<ReportJob, UUID> {
}
