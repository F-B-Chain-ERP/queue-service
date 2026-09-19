package com.erp.queue_service.repository;

import com.erp.core.domain.StoreDailyReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface StoreDailyReportRepository extends JpaRepository<StoreDailyReport, UUID>, JpaSpecificationExecutor<StoreDailyReport> {
}
