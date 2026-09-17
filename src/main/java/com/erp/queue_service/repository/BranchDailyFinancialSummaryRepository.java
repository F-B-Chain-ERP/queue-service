package com.erp.queue_service.repository;

import com.erp.core.domain.BranchDailyFinancialSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface BranchDailyFinancialSummaryRepository
        extends JpaRepository<BranchDailyFinancialSummary, UUID>, JpaSpecificationExecutor<BranchDailyFinancialSummary> {
}
