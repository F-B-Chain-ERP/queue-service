package com.erp.queue_service.handler;

import com.erp.core.enums.ExportFormat;
import com.erp.queue_service.export.ExportStrategy;
import com.erp.queue_service.export.ExportStrategyFactory;
import com.erp.core.report.ReportDataContext;
import com.erp.queue_service.messaging.ReportMessage;
import com.erp.queue_service.metrics.QueueObservabilityMetrics;
import com.erp.queue_service.notification.ReportSseNotifier;
import com.erp.queue_service.service.MinioStorageService;
import com.erp.queue_service.service.ReportJobStateService;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Bộ điều phối trung tâm tiếp nhận Message từ RabbitMQ, định tuyến đến Handler tương ứng,
 * thực thi kết xuất file, tải lên MinIO và cập nhật bản ghi ReportJob trong cơ sở dữ liệu.
 */
@Component
public class ReportJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ReportJobDispatcher.class);
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final List<ModuleReportHandler> handlers;
    private final ExportStrategyFactory strategyFactory;
    private final MinioStorageService minioStorageService;
    private final ReportJobStateService reportJobStateService;
    private final ReportSseNotifier reportSseNotifier;
    private final QueueObservabilityMetrics queueMetrics;

    public ReportJobDispatcher(List<ModuleReportHandler> handlers,
                               ExportStrategyFactory strategyFactory,
                               MinioStorageService minioStorageService,
                               ReportJobStateService reportJobStateService,
                               ReportSseNotifier reportSseNotifier,
                               QueueObservabilityMetrics queueMetrics) {
        this.handlers = handlers;
        this.strategyFactory = strategyFactory;
        this.minioStorageService = minioStorageService;
        this.reportJobStateService = reportJobStateService;
        this.reportSseNotifier = reportSseNotifier;
        this.queueMetrics = queueMetrics;
    }

    /**
     * Điều phối và xử lý trọn vẹn tác vụ báo cáo bất đồng bộ.
     * Lưu ý: Không đặt @Transactional ở cấp phương thức này để không giữ kết nối DB
     * trong suốt quá trình truy vấn nặng và upload file MinIO.
     */
    public void dispatch(ReportMessage message) {
        UUID jobId = message.getJobId();
        Timer.Sample sample = queueMetrics.startJobTimer();
        try {
            MDC.put("jobId", jobId != null ? jobId.toString() : "UNKNOWN");
            MDC.put("module", message.getModule() != null ? message.getModule() : "");
            MDC.put("reportType", message.getReportType() != null ? message.getReportType() : "");

            log.info("[Dispatcher] Bắt đầu xử lý ReportJob ID: {}, Module: {}, Type: {}",
                    jobId, message.getModule(), message.getReportType());

            // 1. Conditional Claim Job (Transaction riêng)
            boolean claimed = reportJobStateService.claimJob(jobId);
            if (!claimed) {
                log.warn("[Dispatcher] Không thể claim ReportJob ID: {} (Job đã được claim, hoàn tất, hoặc đã bị CANCELLED). Bỏ qua xử lý.", jobId);
                queueMetrics.recordJobSkipped(sample, message.getModule(), "NOT_CLAIMED");
                return;
            }

            try {
                // 2. Tìm Handler tương ứng với module
                ModuleReportHandler handler = handlers.stream()
                        .filter(h -> h.supports(message.getModule()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy handler cho module: " + message.getModule()));

                // 3. Trích xuất dữ liệu báo cáo (chạy ngoài transaction DB dài)
                ReportDataContext context = handler.generateReportData(message);

                // 4. Kết xuất ra file theo định dạng (Excel / PDF)
                ExportFormat format = ExportFormat.valueOf(message.getFormat() != null ? message.getFormat() : "EXCEL");
                ExportStrategy strategy = strategyFactory.getStrategy(format);
                byte[] fileBytes = strategy.export(context);

                // 5. Tải file lên MinIO với ObjectKey tất định (Idempotent)
                String timestamp = LocalDateTime.now().format(FILE_DATE_FORMAT);
                String baseName = handler.getBaseFileName(message);
                String originalFileName = baseName + "_" + timestamp + strategy.getFileExtension();
                String objectKey = minioStorageService.buildObjectKey(jobId, originalFileName);

                String fileUrl = minioStorageService.uploadReportWithKey(fileBytes, objectKey, strategy.getContentType());

                // 6. Cập nhật ReportJob sang DONE (Transaction riêng)
                reportJobStateService.markDone(jobId, fileUrl, objectKey);

                // 7. Thông báo Realtime qua Redis Pub/Sub / SSE
                reportSseNotifier.reportDone(message, fileUrl, originalFileName);

                int recordCount = (context != null && context.rows() != null) ? context.rows().size() : 0;
                long fileSize = (fileBytes != null) ? fileBytes.length : 0;
                queueMetrics.recordJobSuccess(sample, message.getModule(), message.getFormat(), fileSize, recordCount);

                log.info("[Dispatcher] Hoàn tất ReportJob ID: {}. ObjectKey: {}, File URL: {}", jobId, objectKey, fileUrl);

            } catch (Exception e) {
                log.error("[Dispatcher] Thất bại khi xử lý ReportJob ID: {}. Lỗi: {}", jobId, e.getMessage(), e);
                queueMetrics.recordJobFailure(sample, message.getModule(), message.getFormat(), e.getClass().getSimpleName());

                // Ghi nhận trạng thái FAILED ngay lập tức bằng transaction riêng biệt
                try {
                    reportJobStateService.markFailed(jobId, e.getMessage());
                } catch (Exception dbEx) {
                    log.error("[Dispatcher] Không thể ghi trạng thái FAILED cho Job {}: {}", jobId, dbEx.getMessage(), dbEx);
                }

                // Bắn thông báo thất bại tới người dùng
                try {
                    reportSseNotifier.reportFailed(message, e.getMessage());
                } catch (Exception sseEx) {
                    log.warn("[Dispatcher] Không thể phát SSE thất bại cho Job {}: {}", jobId, sseEx.getMessage());
                }

                throw new RuntimeException("Lỗi xử lý báo cáo: " + e.getMessage(), e);
            }
        } finally {
            MDC.clear();
        }
    }
}
