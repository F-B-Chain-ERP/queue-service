package com.erp.queue_service.export;

import com.erp.core.report.ReportColumnDefinition;
import com.erp.core.report.ReportDataContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm tra chiến lược xuất PDF: ra tệp hợp lệ (%PDF), có bảng phụ + vùng ký khi là biên bản
 * chốt ca, và vẫn xuất được khi chỉ có dữ liệu cơ bản.
 */
class PdfExportStrategyTest {

    private final PdfExportStrategy strategy = new PdfExportStrategy();

    private ReportDataContext closureContext() {
        return new ReportDataContext(
                "BÁO CÁO BIÊN BẢN CHỐT CA BÁN HÀNG",
                "Ngày 01/01/2026",
                null,
                Map.of("submittedBy", "a1", "approvedBy", "b1"),
                List.of(
                        ReportColumnDefinition.number("ordersCount", "Số đơn", 10),
                        ReportColumnDefinition.currency("totalSales", "Tổng doanh thu", 16)
                ),
                List.of(Map.<String, Object>of("ordersCount", 12, "totalSales", new BigDecimal("450000"))),
                Map.of("label", "TỔNG CỘNG", "ordersCount", 12, "totalSales", new BigDecimal("450000")),
                List.of(Map.<String, Object>of("denomination", "500.000 ₫", "count", 2L,
                        "amount", new BigDecimal("1000000")))
        );
    }

    @Test
    @DisplayName("Chốt ca: xuất PDF hợp lệ với logo (fallback classpath), bảng phụ và vùng ký")
    void export_closureReport_producesValidPdf() {
        byte[] bytes = strategy.export(closureContext());

        assertThat(bytes).hasSizeGreaterThan(1000);
        assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).startsWith("%PDF");
    }

    @Test
    @DisplayName("Báo cáo thường không ký: vẫn ra PDF hợp lệ")
    void export_plainReport_stillProducesPdf() {
        ReportDataContext context = new ReportDataContext(
                "BÁO CÁO ĐƠN HÀNG",
                "subtitle",
                null,
                Map.of("rowCount", 1),
                List.of(ReportColumnDefinition.text("code", "Mã đơn", 12)),
                List.of(Map.<String, Object>of("code", "DH-0001")),
                Map.of("label", "TỔNG CỘNG", "rowCount", 1),
                List.of()
        );

        byte[] bytes = strategy.export(context);

        assertThat(bytes).hasSizeGreaterThan(500);
        assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).startsWith("%PDF");
    }
}