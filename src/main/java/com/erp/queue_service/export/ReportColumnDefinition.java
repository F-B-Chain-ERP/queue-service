package com.erp.queue_service.export;

/**
 * Định nghĩa cấu hình một cột trong bảng dữ liệu báo cáo trong queue-service.
 */
public record ReportColumnDefinition(
        String key,
        String header,
        int width,
        ColumnType type
) {
    public enum ColumnType {
        TEXT,
        NUMBER,
        CURRENCY,
        DATE,
        DATETIME
    }

    public static ReportColumnDefinition text(String key, String header, int width) {
        return new ReportColumnDefinition(key, header, width, ColumnType.TEXT);
    }

    public static ReportColumnDefinition number(String key, String header, int width) {
        return new ReportColumnDefinition(key, header, width, ColumnType.NUMBER);
    }

    public static ReportColumnDefinition currency(String key, String header, int width) {
        return new ReportColumnDefinition(key, header, width, ColumnType.CURRENCY);
    }

    public static ReportColumnDefinition date(String key, String header, int width) {
        return new ReportColumnDefinition(key, header, width, ColumnType.DATE);
    }

    public static ReportColumnDefinition dateTime(String key, String header, int width) {
        return new ReportColumnDefinition(key, header, width, ColumnType.DATETIME);
    }
}
