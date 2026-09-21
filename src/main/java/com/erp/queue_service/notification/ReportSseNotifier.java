package com.erp.queue_service.notification;

import com.erp.queue_service.messaging.ReportMessage;
import com.erp.queue_service.util.QueueRedisKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Xuất bản sự kiện hoàn thành/thiếu báo cáo lên Redis Pub/Sub để backend-service
 * {@code NotificationRedisListener} đẩy realtime tới người dùng qua SSE.
 *
 * <p><b>Hợp đồng kênh:</b> queue publish vào kênh {@code "notification:" + requestedBy}
 * (qua {@link QueueRedisKeys#notificationChannel(UUID)}) — đúng kênh mà backend
 * {@code RedisKeys.notificationChannel(accountId)} đang lắng nghe (prefix {@code notification:*}).
 * Payload JSON format cho SSE:
 * <pre>
 * { "jobId": "...", "module": "STORE", "reportType": "...", "format": "PDF",
 *   "status": "DONE" | "FAILED", "fileName": "...", "fileUrl": "...",
 *   "errorMessage": "...", "requestedBy": "...", "type": "REPORT_DONE" | "REPORT_FAILED",
 *   "completedAt": "ISO-8601", "message": "..." }
 * </pre>
 * Frontend dùng {@code type} = {@code REPORT_DONE}/{@code REPORT_FAILED} để nhận biết sự kiện
 * hoàn tất báo cáo bất đồng bộ và hiển thị đường dẫn tải file hoặc lỗi.
 */
@Component
public class ReportSseNotifier {

    private static final Logger log = LoggerFactory.getLogger(ReportSseNotifier.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public ReportSseNotifier(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    /** Thông báo báo cáo đã xuất file thành công. */
    public void reportDone(ReportMessage message, String fileUrl, String fileName) {
        publish(message, "DONE", "REPORT_DONE", fileUrl, fileName, null);
    }

    /** Thông báo báo cáo xử lý lỗi. */
    public void reportFailed(ReportMessage message, String errorMessage) {
        publish(message, "FAILED", "REPORT_FAILED", null, null, errorMessage);
    }

    private void publish(ReportMessage message, String status, String type,
                         String fileUrl, String fileName, String errorMessage) {
        UUID requestedBy = message.getRequestedBy();
        if (requestedBy == null) {
            log.warn("[SSE] Bỏ qua notify vì thiếu requestedBy (jobId={})", message.getJobId());
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jobId", message.getJobId().toString());
            payload.put("module", message.getModule());
            payload.put("reportType", message.getReportType());
            payload.put("format", message.getFormat());
            payload.put("status", status);
            payload.put("type", type);
            payload.put("fileName", fileName);
            payload.put("fileUrl", fileUrl);
            payload.put("errorMessage", errorMessage);
            payload.put("requestedBy", requestedBy.toString());
            payload.put("completedAt", Instant.now().toString());
            payload.put("message", "DONE".equals(status)
                    ? "Báo cáo đã hoàn tất. Vui lòng tải file trên trang danh sách."
                    : "Báo cáo xử lý thất bại. Vui lòng kiểm tra lại.");

            String json = objectMapper.writeValueAsString(payload);
            stringRedisTemplate.convertAndSend(QueueRedisKeys.notificationChannel(requestedBy), json);
            log.info("[SSE] Đã publish sự kiện {} (jobId={}) lên kênh notification:{}",
                    type, message.getJobId(), requestedBy);
        } catch (Exception e) {
            log.warn("[SSE] Không publish được sự kiện {} (jobId={}): {}",
                    type, message.getJobId(), e.getMessage());
        }
    }
}
