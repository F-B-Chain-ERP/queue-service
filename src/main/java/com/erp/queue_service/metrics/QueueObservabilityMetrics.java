package com.erp.queue_service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service quản trị các chỉ số giám sát vận hành (Observability Metrics) cho Queue Service.
 * Xuất bản trực tiếp sang Prometheus qua endpoint /actuator/prometheus.
 */
@Component
public class QueueObservabilityMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger activeJobsGauge;

    public QueueObservabilityMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.activeJobsGauge = meterRegistry.gauge("erp_queue_active_jobs", new AtomicInteger(0));
    }

    /**
     * Bắt đầu một tác vụ xử lý: Tăng bộ đếm công việc đang thực thi (Active Jobs).
     */
    public Timer.Sample startJobTimer() {
        if (activeJobsGauge != null) {
            activeJobsGauge.incrementAndGet();
        }
        return Timer.start(meterRegistry);
    }

    /**
     * Hoàn tất một tác vụ xử lý: Giảm bộ đếm active jobs, ghi nhận thời gian và bộ đếm tổng.
     */
    public void recordJobSuccess(Timer.Sample sample, String module, String format, long fileSizeBytes, int recordCount) {
        decrementActiveJobs();

        String safeModule = normalizeTag(module);
        String safeFormat = normalizeTag(format);

        // Ghi nhận thời gian hoàn tất
        Timer timer = Timer.builder("erp_queue_job_duration_seconds")
                .description("Thời gian thực thi trọn vẹn của một tác vụ xuất báo cáo (giây)")
                .tag("module", safeModule)
                .tag("format", safeFormat)
                .tag("status", "SUCCESS")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(10))
                .maximumExpectedValue(Duration.ofMinutes(5))
                .register(meterRegistry);
        sample.stop(timer);

        // Ghi nhận số lượng job thành công
        Counter.builder("erp_queue_jobs_total")
                .description("Tổng số lượng tác vụ báo cáo đã xử lý")
                .tag("module", safeModule)
                .tag("format", safeFormat)
                .tag("status", "SUCCESS")
                .register(meterRegistry)
                .increment();

        // Ghi nhận kích thước file tải lên
        if (fileSizeBytes > 0) {
            DistributionSummary.builder("erp_queue_file_size_bytes")
                    .description("Dung lượng file báo cáo sinh ra (bytes)")
                    .baseUnit("bytes")
                    .tag("module", safeModule)
                    .tag("format", safeFormat)
                    .register(meterRegistry)
                    .record(fileSizeBytes);
        }

        // Ghi nhận số lượng dòng dữ liệu
        if (recordCount > 0) {
            DistributionSummary.builder("erp_queue_job_records_count")
                    .description("Số lượng bản ghi nghiệp vụ kết xuất trong báo cáo")
                    .tag("module", safeModule)
                    .register(meterRegistry)
                    .record(recordCount);
        }
    }

    /**
     * Ghi nhận tác vụ thất bại.
     */
    public void recordJobFailure(Timer.Sample sample, String module, String format, String errorType) {
        decrementActiveJobs();

        String safeModule = normalizeTag(module);
        String safeFormat = normalizeTag(format);
        String safeErrorType = normalizeTag(errorType);

        if (sample != null) {
            Timer timer = Timer.builder("erp_queue_job_duration_seconds")
                    .description("Thời gian thực thi trọn vẹn của một tác vụ xuất báo cáo (giây)")
                    .tag("module", safeModule)
                    .tag("format", safeFormat)
                    .tag("status", "FAILED")
                    .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                    .register(meterRegistry);
            sample.stop(timer);
        }

        Counter.builder("erp_queue_jobs_total")
                .description("Tổng số lượng tác vụ báo cáo đã xử lý")
                .tag("module", safeModule)
                .tag("format", safeFormat)
                .tag("status", "FAILED")
                .register(meterRegistry)
                .increment();

        Counter.builder("erp_queue_job_errors_total")
                .description("Tổng số lỗi phát sinh trong quá trình xử lý queue")
                .tag("module", safeModule)
                .tag("error_type", safeErrorType)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Ghi nhận tác vụ bị bỏ qua (đã được claim trước hoặc bị hủy).
     */
    public void recordJobSkipped(Timer.Sample sample, String module, String reason) {
        decrementActiveJobs();

        String safeModule = normalizeTag(module);
        String safeReason = normalizeTag(reason);

        Counter.builder("erp_queue_jobs_total")
                .description("Tổng số lượng tác vụ báo cáo đã xử lý")
                .tag("module", safeModule)
                .tag("format", "NONE")
                .tag("status", "SKIPPED_" + safeReason)
                .register(meterRegistry)
                .increment();
    }

    private void decrementActiveJobs() {
        if (activeJobsGauge != null && activeJobsGauge.get() > 0) {
            activeJobsGauge.decrementAndGet();
        }
    }

    private String normalizeTag(String val) {
        if (val == null || val.isBlank()) {
            return "UNKNOWN";
        }
        return val.trim().toUpperCase().replaceAll("[^A-Z0-9_]", "_");
    }
}
