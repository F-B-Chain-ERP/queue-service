package com.erp.queue_service.handler;

import com.erp.core.enums.ExportFormat;
import com.erp.core.report.ReportDataContext;
import com.erp.queue_service.export.ExportStrategy;
import com.erp.queue_service.export.ExportStrategyFactory;
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

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Bộ điều phối trung tâm tiếp nhận Message từ RabbitMQ, định tuyến đến Handler tương ứng,
 * thực thi kết xuất file theo mô hình streaming/file tạm, tải lên MinIO và cập nhật
 * bản ghi ReportJob trong cơ sở dữ liệu kèm logging & metrics chi tiết.
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

    private static String getMemoryStats() {
        long free = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        long total = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        long max = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        long used = total - free;
        return String.format("used=%dMB, total=%dMB, max=%dMB", used, total, max);
    }

    /**
     * Điều phối và xử lý trọn vẹn tác vụ báo cáo bất đồng bộ.
     * Không đặt @Transactional ở cấp phương thức này để không giữ kết nối DB
     * trong suốt quá trình truy vấn nặng và upload file MinIO.
     */
    public void dispatch(ReportMessage message) {
        dispatch(message, false);
    }

    public void dispatch(ReportMessage message, boolean redelivered) {
        UUID jobId = message.getJobId();
        Timer.Sample sample = queueMetrics.startJobTimer();
        File tempFile = null;
        long totalStartTime = System.currentTimeMillis();

        try {
            MDC.put("jobId", jobId != null ? jobId.toString() : "UNKNOWN");
            MDC.put("module", message.getModule() != null ? message.getModule() : "");
            MDC.put("reportType", message.getReportType() != null ? message.getReportType() : "");

            log.info("[Dispatcher] [START] Bắt đầu tiếp nhận ReportJob ID: {}, Module: {}, Type: {}, Format: {} | RAM: {}",
                    jobId, message.getModule(), message.getReportType(), message.getFormat(), getMemoryStats());

            // 1. Conditional Claim Job (Transaction riêng)
            boolean claimed = reportJobStateService.claimJob(jobId, redelivered);
            if (!claimed) {
                log.warn("[Dispatcher] [SKIPPED] Không thể claim ReportJob ID: {} (redelivered={}; job đang được xử lý hoặc đã kết thúc). Bỏ qua xử lý.",
                        jobId, redelivered);
                queueMetrics.recordJobSkipped(sample, message.getModule(), "NOT_CLAIMED");
                return;
            }

            try {
                // 2. Tìm Handler tương ứng với module
                ModuleReportHandler handler = handlers.stream()
                        .filter(h -> h.supports(message.getModule()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy handler cho module: " + message.getModule()));

                // 3. Trích xuất dữ liệu báo cáo
                long queryStartTime = System.currentTimeMillis();
                log.info("[Dispatcher] [QUERY_START] Bắt đầu trích xuất dữ liệu từ handler: {} | RAM: {}",
                        handler.getClass().getSimpleName(), getMemoryStats());

                ReportDataContext context = handler.generateReportData(message);
                long queryDuration = System.currentTimeMillis() - queryStartTime;
                int rowCount = (context != null && context.rows() != null) ? context.rows().size() : 0;

                // Cập nhật heartbeat sau bước query
                reportJobStateService.updateHeartbeat(jobId);

                log.info("[Dispatcher] [QUERY_DONE] Đã trích xuất xong {} dòng dữ liệu trong {} ms | RAM: {}",
                        rowCount, queryDuration, getMemoryStats());

                // 4. Kết xuất ra file theo định dạng (SXSSF Excel / PDF)
                long renderStartTime = System.currentTimeMillis();
                ExportFormat format = ExportFormat.valueOf(message.getFormat() != null ? message.getFormat() : "EXCEL");
                ExportStrategy strategy = strategyFactory.getStrategy(format);

                log.info("[Dispatcher] [RENDER_START] Bắt đầu kết xuất ra file định dạng {} | RAM: {}",
                        format, getMemoryStats());

                String timestamp = LocalDateTime.now().format(FILE_DATE_FORMAT);
                String baseName = handler.getBaseFileName(message);
                String originalFileName = baseName + "_" + timestamp + strategy.getFileExtension();
                String objectKey = minioStorageService.buildObjectKey(jobId, originalFileName);

                long fileSize = 0;
                String fileUrl = null;

                try {
                    tempFile = strategy.exportToTempFile(context);
                    if (tempFile != null && tempFile.exists() && tempFile.length() > 0) {
                        fileSize = tempFile.length();
                    }
                } catch (Exception ex) {
                    log.warn("[Dispatcher] exportToTempFile không khả dụng ({}), chuyển sang export byte[] thông thường", ex.getMessage());
                }

                long renderDuration = System.currentTimeMillis() - renderStartTime;

                // Cập nhật heartbeat sau bước render
                reportJobStateService.updateHeartbeat(jobId);

                // 5. Tải file lên MinIO với ObjectKey tất định
                long uploadStartTime = System.currentTimeMillis();
                log.info("[Dispatcher] [UPLOAD_START] Tải file lên MinIO (bucket key: {})", objectKey);

                if (tempFile != null && tempFile.exists() && tempFile.length() > 0) {
                    log.info("[Dispatcher] [RENDER_DONE] Đã ghi ra file tạm {} (dung lượng: {} bytes ~ {} KB) trong {} ms | RAM: {}",
                            tempFile.getName(), fileSize, fileSize / 1024, renderDuration, getMemoryStats());
                    fileUrl = minioStorageService.uploadReportFromFile(tempFile, objectKey, strategy.getContentType());
                } else {
                    byte[] fileBytes = strategy.export(context);
                    fileSize = fileBytes != null ? fileBytes.length : 0;
                    log.info("[Dispatcher] [RENDER_DONE] Đã kết xuất byte[] (dung lượng: {} bytes ~ {} KB) trong {} ms | RAM: {}",
                            fileSize, fileSize / 1024, renderDuration, getMemoryStats());
                    fileUrl = minioStorageService.uploadReportWithKey(fileBytes, objectKey, strategy.getContentType());
                }

                long uploadDuration = System.currentTimeMillis() - uploadStartTime;
                log.info("[Dispatcher] [UPLOAD_DONE] Tải lên MinIO hoàn tất trong {} ms. URL: {}", uploadDuration, fileUrl);

                // 6. Cập nhật ReportJob sang DONE (Transaction riêng)
                reportJobStateService.markDone(jobId, fileUrl, objectKey);

                // 7. Thông báo Realtime qua Redis Pub/Sub / SSE
                reportSseNotifier.reportDone(message, fileUrl, originalFileName);

                queueMetrics.recordJobSuccess(sample, message.getModule(), message.getFormat(), fileSize, rowCount);

                long totalDuration = System.currentTimeMillis() - totalStartTime;
                log.info("[Dispatcher] [SUCCESS] Hoàn tất toàn bộ ReportJob ID: {} trong {} ms (Query: {} ms, Render: {} ms, Upload: {} ms). File: {}",
                        jobId, totalDuration, queryDuration, renderDuration, uploadDuration, objectKey);

            } catch (Exception e) {
                log.error("[Dispatcher] [FAILED] Thất bại khi xử lý ReportJob ID: {}. Lỗi: {}", jobId, e.getMessage(), e);
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
            } finally {
                // Xóa file tạm trên đĩa để không làm đầy storage
                if (tempFile != null && tempFile.exists()) {
                    boolean deleted = tempFile.delete();
                    if (!deleted) {
                        tempFile.deleteOnExit();
                    }
                }
            }
        } finally {
            MDC.clear();
        }
    }
}
