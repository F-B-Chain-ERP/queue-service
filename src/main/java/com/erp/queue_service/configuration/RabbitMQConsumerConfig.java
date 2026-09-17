package com.erp.queue_service.configuration;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Cấu hình RabbitMQ Consumer trong queue-service.
 */
@Configuration
public class RabbitMQConsumerConfig {

    public static final String REPORT_EXCHANGE = "erp.report.exchange";
    public static final String REPORT_DL_EXCHANGE = "erp.report.dl.exchange";

    public static final String REPORT_QUEUE = "erp.report.queue";
    public static final String REPORT_DLQ = "erp.report.dlq";

    public static final String REPORT_ROUTING_KEY = "report.generate";
    public static final String REPORT_DL_ROUTING_KEY = "report.dead";

    @Bean
    public DirectExchange reportExchange() {
        return new DirectExchange(REPORT_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange reportDlExchange() {
        return new DirectExchange(REPORT_DL_EXCHANGE, true, false);
    }

    @Bean
    public Queue reportQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", REPORT_DL_EXCHANGE);
        args.put("x-dead-letter-routing-key", REPORT_DL_ROUTING_KEY);
        args.put("x-message-ttl", 1800000);
        return new Queue(REPORT_QUEUE, true, false, false, args);
    }

    @Bean
    public Queue reportDlq() {
        return new Queue(REPORT_DLQ, true, false, false);
    }

    @Bean
    public Binding reportBinding(Queue reportQueue, DirectExchange reportExchange) {
        return BindingBuilder.bind(reportQueue).to(reportExchange).with(REPORT_ROUTING_KEY);
    }

    @Bean
    public Binding reportDlqBinding(Queue reportDlq, DirectExchange reportDlExchange) {
        return BindingBuilder.bind(reportDlq).to(reportDlExchange).with(REPORT_DL_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
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
