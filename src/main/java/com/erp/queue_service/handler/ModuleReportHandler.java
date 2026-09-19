package com.erp.queue_service.handler;

import com.erp.queue_service.export.ReportDataContext;
import com.erp.queue_service.messaging.ReportMessage;

/**
 * Interface cho các bộ xử lý tạo dữ liệu báo cáo theo từng phân hệ trong queue-service.
 */
public interface ModuleReportHandler {

    /**
     * Kiểm tra handler này có hỗ trợ phân hệ (STORE, POS, PROC, INV, FIN) hay không.
     */
    boolean supports(String module);

    /**
     * Truy vấn cơ sở dữ liệu và xây dựng ngữ cảnh dữ liệu báo cáo (tiêu đề, các cột, các dòng).
     */
    ReportDataContext generateReportData(ReportMessage message);

    /**
     * Tên tệp cơ sở cho báo cáo này.
     */
    String getBaseFileName(ReportMessage message);
}
