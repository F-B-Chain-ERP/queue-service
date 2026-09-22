package com.erp.queue_service.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Optional filters shared by the POS detail and sales-summary reports.
 */
public record PosReportCriteria(
        UUID branchId,
        String orderType,
        String status,
        Instant fromInstant,
        Instant toInstant
) {
}
