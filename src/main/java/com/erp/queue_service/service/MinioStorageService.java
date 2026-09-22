package com.erp.queue_service.service;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
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
     * Tải dữ liệu nhị phân lên MinIO theo objectKey chỉ định trước (Idempotent).
     *
     * @param fileBytes   Nội dung file
     * @param objectKey   Khóa định danh tệp (reports/yyyy/MM/dd/jobId/filename)
     * @param contentType MIME type
     * @return URL tải file
     */
    public String uploadReportWithKey(byte[] fileBytes, String objectKey, String contentType) {
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
            log.error("[MinIO] Lỗi khi tải tệp lên MinIO (key={}): {}", objectKey, e.getMessage(), e);
            throw new RuntimeException("Lỗi lưu trữ tệp báo cáo: " + e.getMessage(), e);
        }
    }

    /**
     * Tải tệp tạm trực tiếp lên MinIO theo objectKey chỉ định trước bằng InputStream.
     * Tránh đọc toàn bộ file thành mảng byte[] trong heap RAM.
     *
     * @param file        File tạm thời chứa dữ liệu báo cáo
     * @param objectKey   Khóa định danh tệp
     * @param contentType MIME type
     * @return URL tải file
     */
    public String uploadReportFromFile(File file, String objectKey, String contentType) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(in, file.length(), -1)
                            .contentType(contentType)
                            .build()
            );
            log.info("[MinIO] Đã tải lên báo cáo từ file tạm thành công: key '{}', size: {} bytes", objectKey, file.length());
            return publicUrl + "/" + bucketName + "/" + objectKey;
        } catch (Exception e) {
            log.error("[MinIO] Lỗi khi tải tệp tạm lên MinIO (key={}): {}", objectKey, e.getMessage(), e);
            throw new RuntimeException("Lỗi lưu trữ tệp báo cáo: " + e.getMessage(), e);
        }
    }

    /**
     * Xóa tệp báo cáo khỏi MinIO (phục vụ retention cleanup).
     */
    public void deleteReport(String objectKey) {
        try {
            minioClient.removeObject(
                    io.minio.RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );
            log.info("[MinIO] Đã xóa tệp báo cáo hết hạn: key '{}'", objectKey);
        } catch (Exception e) {
            log.warn("[MinIO] Không thể xóa tệp báo cáo (key={}): {}", objectKey, e.getMessage());
        }
    }

    public String buildObjectKey(UUID jobId, String fileName) {
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        return "reports/" + datePath + "/" + jobId + "/" + fileName;
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
        return uploadReportWithKey(fileBytes, objectKey, contentType);
    }
}
