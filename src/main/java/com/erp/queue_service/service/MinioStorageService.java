package com.erp.queue_service.service;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Service lưu trữ tệp kết xuất báo cáo lên MinIO object storage.
 */
@Service
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final MinioClient minioClient;

    @Value("${app.minio.bucket-name:erp-reports}")
    private String bucketName;

    @Value("${app.minio.public-url:/storage}")
    private String publicUrl;

    public MinioStorageService(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    /**
     * Tải dữ liệu nhị phân lên MinIO và trả về đường dẫn URL công khai để tải xuống.
     *
     * @param fileBytes    Nội dung file
     * @param originalName Tên file gốc
     * @param contentType  MIME type
     * @return URL tải file
     */
    public String uploadReport(byte[] fileBytes, String originalName, String contentType) {
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String objectKey = "reports/" + datePath + "/" + UUID.randomUUID() + "_" + originalName;

        try (ByteArrayInputStream in = new ByteArrayInputStream(fileBytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(in, fileBytes.length, -1)
                            .contentType(contentType)
                            .build()
            );
            log.info("[MinIO] Đã tải lên báo cáo thành công vào bucket '{}', key '{}'", bucketName, objectKey);
            return publicUrl + "/" + bucketName + "/" + objectKey;
        } catch (Exception e) {
            log.error("[MinIO] Lỗi khi tải tệp lên MinIO: {}", e.getMessage(), e);
            throw new RuntimeException("Lỗi lưu trữ tệp báo cáo: " + e.getMessage(), e);
        }
    }
}
