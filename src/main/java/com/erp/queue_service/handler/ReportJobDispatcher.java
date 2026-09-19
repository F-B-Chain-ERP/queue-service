package com.erp.queue_service.handler;

import com.erp.core.domain.ReportJob;
import com.erp.core.enums.ExportFormat;
import com.erp.core.enums.ReportStatus;
import com.erp.queue_service.export.ExportStrategy;
import com.erp.queue_service.export.ExportStrategyFactory;
import com.erp.queue_service.export.ReportDataContext;
import com.erp.queue_service.messaging.ReportMessage;
import com.erp.queue_service.repository.ReportJobRepository;
import com.erp.queue_service.service.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

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
    private final ReportJobRepository reportJobRepository;

    public ReportJobDispatcher(List<ModuleReportHandler> handlers,
                               ExportStrategyFactory strategyFactory,
                               MinioStorageService minioStorageService,
                               ReportJobRepository reportJobRepository) {
        this.handlers = handlers;
        this.strategyFactory = strategyFactory;
        this.minioStorageService = minioStorageService;
        this.reportJobRepository = reportJobRepository;
    }

    /**
     * Điều phối và xử lý trọn vẹn tác vụ báo cáo bất đồng bộ.
     */
    @Transactional
    public void dispatch(ReportMessage message) {
        log.info("[Dispatcher] Bắt đầu xử lý ReportJob ID: {}, Module: {}, Type: {}",
                message.getJobId(), message.getModule(), message.getReportType());

        // 1. Cập nhật trạng thái tác vụ sang PROCESSING
        ReportJob job = reportJobRepository.findById(message.getJobId()).orElse(null);
        if (job == null) {
            log.warn("[Dispatcher] Không tìm thấy ReportJob ID: {}", message.getJobId());
            return;
        }

        job.setStatus(ReportStatus.PROCESSING.name());
        job.setStartedAt(Instant.now());
        reportJobRepository.save(job);

        try {
            // 2. Tìm Handler tương ứng với module
            ModuleReportHandler handler = handlers.stream()
                    .filter(h -> h.supports(message.getModule()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy handler cho module: " + message.getModule()));

            // 3. Trích xuất dữ liệu báo cáo
            ReportDataContext context = handler.generateReportData(message);

            // 4. Kết xuất ra file theo định dạng (Excel / PDF)
            ExportFormat format = ExportFormat.valueOf(message.getFormat() != null ? message.getFormat() : "EXCEL");
            ExportStrategy strategy = strategyFactory.getStrategy(format);
            byte[] fileBytes = strategy.export(context);

            // 5. Tải file lên MinIO
            String timestamp = LocalDateTime.now().format(FILE_DATE_FORMAT);
            String baseName = handler.getBaseFileName(message);
            String originalFileName = baseName + "_" + timestamp + strategy.getFileExtension();
            String fileUrl = minioStorageService.uploadReport(fileBytes, originalFileName, strategy.getContentType());

            // 6. Cập nhật ReportJob sang DONE
            job.setStatus(ReportStatus.DONE.name());
            job.setFileUrl(fileUrl);
            job.setCompletedAt(Instant.now());
            reportJobRepository.save(job);

            log.info("[Dispatcher] Hoàn tất ReportJob ID: {}. File URL: {}", message.getJobId(), fileUrl);

        } catch (Exception e) {
            log.error("[Dispatcher] Thất bại khi xử lý ReportJob ID: {}. Lỗi: {}", message.getJobId(), e.getMessage(), e);
            job.setStatus(ReportStatus.FAILED.name());
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(Instant.now());
            reportJobRepository.save(job);
            throw new RuntimeException("Lỗi xử lý báo cáo: " + e.getMessage(), e);
        }
    }
}
