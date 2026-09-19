package com.erp.queue_service.messaging;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Message payload tiêu thụ từ RabbitMQ queue.
 */
public class ReportMessage implements Serializable {

    private UUID jobId;
    private String module;
    private String reportType;
    private String format;
    private UUID requestedBy;
    private UUID branchId;
    private Map<String, Object> params;
    private Instant createdAt;

    public ReportMessage() {
    }

    public ReportMessage(UUID jobId, String module, String reportType, String format,
                         UUID requestedBy, UUID branchId, Map<String, Object> params, Instant createdAt) {
        this.jobId = jobId;
        this.module = module;
        this.reportType = reportType;
        this.format = format;
        this.requestedBy = requestedBy;
        this.branchId = branchId;
        this.params = params;
        this.createdAt = createdAt;
    }

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getReportType() {
        return reportType;
    }

    public void setReportType(String reportType) {
        this.reportType = reportType;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(UUID requestedBy) {
        this.requestedBy = requestedBy;
    }

    public UUID getBranchId() {
        return branchId;
    }

    public void setBranchId(UUID branchId) {
        this.branchId = branchId;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
