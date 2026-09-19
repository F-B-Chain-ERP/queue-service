package com.erp.queue_service.configuration;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Cấu hình RabbitMQ Consumer trong queue-service.
 * Các thông số được đọc từ cấu hình application (app.rabbitmq.report.*).
 */
@Configuration
public class RabbitMQConsumerConfig {
    @Value("${app.rabbitmq.report.exchange:erp.report.exchange}")
    private String reportExchange;

    @Value("${app.rabbitmq.report.dl-exchange:erp.report.dl.exchange}")
    private String reportDlExchange;

    @Value("${app.rabbitmq.report.queue:erp.report.queue}")
    private String reportQueue;

    @Value("${app.rabbitmq.report.dlq:erp.report.dlq}")
    private String reportDlq;

    @Value("${app.rabbitmq.report.routing-key:report.generate}")
    private String reportRoutingKey;

    @Value("${app.rabbitmq.report.dl-routing-key:report.dead}")
    private String reportDlRoutingKey;

    @Value("${app.rabbitmq.report.ttl:1800000}")
    private int reportTtl;

    @Bean
    public DirectExchange reportExchange() {
        return new DirectExchange(reportExchange, true, false);
    }

    @Bean
    public DirectExchange reportDlExchange() {
        return new DirectExchange(reportDlExchange, true, false);
    }

    @Bean
    public Queue reportQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", reportDlExchange);
        args.put("x-dead-letter-routing-key", reportDlRoutingKey);
        // Message TTL: mặc định 30 phút = 1800000 ms
        args.put("x-message-ttl", reportTtl);
        return new Queue(reportQueue, true, false, false, args);
    }

    @Bean
    public Queue reportDlq() {
        return new Queue(reportDlq, true, false, false);
    }

    @Bean
    public Binding reportBinding(Queue reportQueue, DirectExchange reportExchange) {
        return BindingBuilder.bind(reportQueue).to(reportExchange).with(reportRoutingKey);
    }

    @Bean
    public Binding reportDlqBinding(Queue reportDlq, DirectExchange reportDlExchange) {
        return BindingBuilder.bind(reportDlq).to(reportDlExchange).with(reportDlRoutingKey);
    }

    public String getReportExchange() {
        return reportExchange;
    }

    public String getReportDlExchange() {
        return reportDlExchange;
    }

    public String getReportQueue() {
        return reportQueue;
    }

    public String getReportDlq() {
        return reportDlq;
    }

    public String getReportRoutingKey() {
        return reportRoutingKey;
    }

    public String getReportDlRoutingKey() {
        return reportDlRoutingKey;
    }

    public int getReportTtl() {
        return reportTtl;
    }

    @Bean
    public JacksonJsonMessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(1);
        return factory;
    }
}
