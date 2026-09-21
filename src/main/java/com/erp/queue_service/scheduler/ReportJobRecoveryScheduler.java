package com.erp.queue_service.scheduler;

import com.erp.core.domain.ReportJob;
import com.erp.queue_service.messaging.ReportMessage;
import com.erp.queue_service.notification.ReportSseNotifier;
import com.erp.queue_service.repository.ReportJobRepository;
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
 * Scheduled job tự động rà soát và khôi phục các tác vụ báo cáo bị treo (Stalled/Zombie Jobs).
 */
@Component
public class ReportJobRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportJobRecoveryScheduler.class);

    private final ReportJobRepository reportJobRepository;
    private final ReportSseNotifier reportSseNotifier;

    @Value("${app.report.async-timeout-minutes:30}")
    private int timeoutMinutes;

    @Value("${app.report.max-attempts:3}")
    private int maxAttempts;

    public ReportJobRecoveryScheduler(ReportJobRepository reportJobRepository,
                                      ReportSseNotifier reportSseNotifier) {
        this.reportJobRepository = reportJobRepository;
        this.reportSseNotifier = reportSseNotifier;
    }

    /**
     * Chạy định kỳ mỗi 5 phút để tìm kiếm các job đang PROCESSING quá thời gian cho phép.
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    @Transactional
    public void recoverStalledJobs() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(timeoutMinutes));
        List<ReportJob> stalledJobs = reportJobRepository.findStalledJobs(cutoff);

        if (stalledJobs.isEmpty()) {
            return;
        }

        log.warn("[RecoveryScheduler] Phát hiện {} tác vụ báo cáo bị treo vượt quá {} phút.",
                stalledJobs.size(), timeoutMinutes);

        for (ReportJob job : stalledJobs) {
            int attempts = job.getAttemptCount() != null ? job.getAttemptCount() : 1;
            if (attempts < maxAttempts) {
                log.info("[RecoveryScheduler] Đưa Job {} trở lại PENDING để thử lại lần {}.", job.getId(), attempts + 1);
                job.setStatus("PENDING");
                job.setNextRetryAt(Instant.now().plusSeconds(60));
                job.setLastError("Hệ thống khôi phục sau timeout xử lý (" + timeoutMinutes + " phút)");
            } else {
                log.error("[RecoveryScheduler] Job {} đã thử {} lần nhưng vẫn timeout. Đánh dấu FAILED.", job.getId(), attempts);
                job.setStatus("FAILED");
                job.setErrorMessage("Tác vụ xử lý quá thời gian tối đa (" + timeoutMinutes + " phút)");
                job.setLastError(job.getErrorMessage());
                job.setCompletedAt(Instant.now());

                // Bắn thông báo thất bại tới người dùng
                ReportMessage msg = new ReportMessage(
                        job.getId(),
                        job.getModule(),
                        job.getReportType(),
                        job.getFormat(),
                        job.getRequestedBy(),
                        job.getBranchId(),
                        null,
                        job.getCreatedAt()
                );
                reportSseNotifier.reportFailed(msg, job.getErrorMessage());
            }
        }

        reportJobRepository.saveAll(stalledJobs);
    }
}
