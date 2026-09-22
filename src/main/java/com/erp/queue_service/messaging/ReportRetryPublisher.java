package com.erp.queue_service.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Publishes recovered report jobs back to the main report queue. */
@Component
public class ReportRetryPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReportRetryPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;

    public ReportRetryPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${app.rabbitmq.report.exchange:erp.report.exchange}") String exchange,
            @Value("${app.rabbitmq.report.routing-key:report.generate}") String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    public void publish(ReportMessage message) {
        rabbitTemplate.convertAndSend(exchange, routingKey, message);
        log.info("[RecoveryPublisher] Đã đưa lại ReportJob {} vào queue.", message.getJobId());
    }
}
