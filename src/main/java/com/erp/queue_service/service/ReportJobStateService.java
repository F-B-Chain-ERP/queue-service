package com.erp.queue_service.service;

import com.erp.queue_service.repository.ReportJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Service chuyên trách cập nhật trạng thái ReportJob đảm bảo tính độc lập và tức thời của các transaction.
 *
 * <p>Mỗi thao tác chuyển trạng thái (CLAIM, DONE, FAILED, HEARTBEAT) được bọc trong một
 * transaction riêng biệt ({@code Propagation.REQUIRES_NEW}) để:
 * <ul>
 *   <li>Tránh giữ kết nối DB trong suốt quá trình truy vấn dữ liệu nặng và upload MinIO.</li>
 *   <li>Đảm bảo trạng thái FAILED luôn được commit ngay cả khi logic xử lý chính gặp ngoại lệ.</li>
 *   <li>Khắc phục triệt để lỗi Spring AOP self-invocation (phương thức gọi nội bộ trong cùng bean không đi qua proxy).</li>
 * </ul>
 */
@Service
public class ReportJobStateService {

    private final ReportJobRepository reportJobRepository;

    public ReportJobStateService(ReportJobRepository reportJobRepository) {
        this.reportJobRepository = reportJobRepository;
    }

    /**
     * Conditional claim: Chỉ nhận việc khi trạng thái hiện tại là PENDING.
     * Commit ngay lập tức trong transaction độc lập.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimJob(UUID jobId, boolean allowProcessingReclaim) {
        int updated = reportJobRepository.claimJob(jobId, Instant.now(), allowProcessingReclaim);
        return updated > 0;
    }

    /**
     * Cập nhật trạng thái DONE kèm fileUrl và objectKey.
     * Commit ngay lập tức trong transaction độc lập.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDone(UUID jobId, String fileUrl, String objectKey) {
        reportJobRepository.markDone(jobId, fileUrl, objectKey, Instant.now());
    }

    /**
     * Cập nhật trạng thái FAILED kèm thông tin lỗi.
     * Commit ngay lập tức trong transaction độc lập.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID jobId, String errorMessage) {
        reportJobRepository.markFailed(jobId, errorMessage, Instant.now());
    }

    /**
     * Cập nhật heartbeat định kỳ để thông báo worker vẫn đang xử lý.
     * Commit ngay lập tức trong transaction độc lập.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateHeartbeat(UUID jobId) {
        reportJobRepository.updateHeartbeat(jobId, Instant.now());
    }
}
