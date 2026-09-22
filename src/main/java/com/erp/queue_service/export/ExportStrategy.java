package com.erp.queue_service.export;

import com.erp.core.enums.ExportFormat;
import com.erp.core.report.ReportDataContext;

import java.io.File;
import java.io.OutputStream;

/**
 * Interface Strategy Pattern định nghĩa hành vi kết xuất báo cáo trong queue-service.
 * Hỗ trợ cả byte[] cho file nhỏ lẫn streaming/file tạm cho khối lượng dữ liệu lớn.
 */
public interface ExportStrategy {

    ExportFormat getSupportedFormat();

    byte[] export(ReportDataContext context);

    default void exportToStream(ReportDataContext context, OutputStream outputStream) {
        try {
            byte[] bytes = export(context);
            outputStream.write(bytes);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Lỗi ghi dữ liệu ra stream: " + e.getMessage(), e);
        }
    }

    default File exportToTempFile(ReportDataContext context) {
        try {
            File tempFile = File.createTempFile("report_export_", getFileExtension());
            try (OutputStream out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(tempFile))) {
                exportToStream(context, out);
            }
            return tempFile;
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi kết xuất báo cáo ra file tạm: " + e.getMessage(), e);
        }
    }

    String getContentType();

    String getFileExtension();
}
