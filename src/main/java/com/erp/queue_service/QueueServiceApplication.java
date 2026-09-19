package com.erp.queue_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Điểm khởi chạy của dịch vụ xử lý hàng đợi và tác vụ xuất báo cáo (queue-service).
 */
@SpringBootApplication
@EntityScan(basePackages = {"com.erp.core.domain"})
@EnableJpaRepositories(basePackages = {"com.erp.queue_service.repository"})
public class QueueServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(QueueServiceApplication.class, args);
    }
}
