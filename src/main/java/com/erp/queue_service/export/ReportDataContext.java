package com.erp.queue_service.export;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Ngữ cảnh dữ liệu dùng để kết xuất file báo cáo trong queue-service.
 */
public record ReportDataContext(
        String title,
        String subtitle,
        List<ReportColumnDefinition> columns,
        List<Map<String, Object>> rows
) {
    public ReportDataContext {
        columns = columns != null ? List.copyOf(columns) : Collections.emptyList();
        rows = rows != null ? List.copyOf(rows) : Collections.emptyList();
    }
}
