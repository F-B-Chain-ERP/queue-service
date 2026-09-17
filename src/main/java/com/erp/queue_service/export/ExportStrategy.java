package com.erp.queue_service.export;

import com.erp.core.enums.ExportFormat;

/**
 * Interface Strategy Pattern định nghĩa hành vi kết xuất báo cáo trong queue-service.
 */
public interface ExportStrategy {

    ExportFormat getSupportedFormat();

    byte[] export(ReportDataContext context);

    String getContentType();

    String getFileExtension();
}
