package com.erp.queue_service.scheduler;

import com.erp.core.domain.ReportJob;
import com.erp.queue_service.repository.ReportJobRepository;
import com.erp.queue_service.service.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Scheduled job tự động dọn dẹp các tệp báo cáo cũ quá hạn lưu trữ (Data Retention Policy).
 */
@Component
public class ReportFileCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportFileCleanupScheduler.class);

    private final ReportJobRepository reportJobRepository;
    private final MinioStorageService minioStorageService;

    @Value("${app.report.retention-days:30}")
    private int retentionDays;

    public ReportFileCleanupScheduler(ReportJobRepository reportJobRepository,
                                     MinioStorageService minioStorageService) {
        this.reportJobRepository = reportJobRepository;
        this.minioStorageService = minioStorageService;
    }

    /**
     * Chạy vào lúc 03:00 sáng mỗi ngày để dọn dẹp các tệp báo cáo quá hạn 30 ngày.
     */
    @Scheduled(cron = "${app.report.cleanup-cron:0 0 3 * * ?}")
    @Transactional
    public void cleanupExpiredReports() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        List<ReportJob> expiredJobs = reportJobRepository.findExpiredJobs(cutoff);

        if (expiredJobs.isEmpty()) {
            return;
        }

        log.info("[CleanupScheduler] Tìm thấy {} báo cáo quá hạn {} ngày. Bắt đầu thu hồi dung lượng MinIO.",
                expiredJobs.size(), retentionDays);

        for (ReportJob job : expiredJobs) {
            if (job.getObjectKey() != null && !job.getObjectKey().isBlank()) {
                minioStorageService.deleteReport(job.getObjectKey());
            }
            job.setStatus("EXPIRED");
            job.setExpiredAt(Instant.now());
        }

        reportJobRepository.saveAll(expiredJobs);
        log.info("[CleanupScheduler] Hoàn tất dọn dẹp {} báo cáo hết hạn.", expiredJobs.size());
    }
}
