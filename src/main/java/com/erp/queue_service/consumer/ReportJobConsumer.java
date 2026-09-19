package com.erp.queue_service.consumer;

import com.erp.queue_service.handler.ReportJobDispatcher;
import com.erp.queue_service.messaging.ReportMessage;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Consumer tiêu thụ thông điệp yêu cầu xuất báo cáo từ RabbitMQ.
 */
@Component
public class ReportJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReportJobConsumer.class);

    private final ReportJobDispatcher reportJobDispatcher;

    public ReportJobConsumer(ReportJobDispatcher reportJobDispatcher) {
        this.reportJobDispatcher = reportJobDispatcher;
    }

    /**
     * Tiếp nhận message từ hàng đợi {@code erp.report.queue}.
     * Sử dụng xác nhận thủ công (MANUAL ACK) để đảm bảo không mất thông điệp khi xảy ra sự cố.
     */
    @RabbitListener(queues = "${app.rabbitmq.report.queue:erp.report.queue}", containerFactory = "rabbitListenerContainerFactory")
    public void consumeReportJob(ReportMessage message, Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        log.info("[RabbitMQ-Consumer] Tiếp nhận thông điệp báo cáo: JobID={}, Module={}, Type={}",
                message.getJobId(), message.getModule(), message.getReportType());

        try {
            reportJobDispatcher.dispatch(message);
            channel.basicAck(deliveryTag, false);
            log.info("[RabbitMQ-Consumer] Đã ACK thành công cho JobID={}", message.getJobId());
        } catch (Exception e) {
            log.error("[RabbitMQ-Consumer] Lỗi khi xử lý thông điệp JobID={}: {}", message.getJobId(), e.getMessage());
            // NACK và không requeue (requeue = false) để chuyển vào Dead Letter Queue (DLQ)
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
