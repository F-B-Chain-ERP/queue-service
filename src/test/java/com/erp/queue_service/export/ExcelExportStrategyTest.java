package com.erp.queue_service.export;

import com.erp.core.report.ReportColumnDefinition;
import com.erp.core.report.ReportDataContext;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm tra chiến lược xuất Excel: tạo đủ 2 sheet, nhúng logo (fallback từ classpath của
 * {@code /report/logo-erp.png} khi không có file ngoài hệ thống) và định dạng kiểu cột.
 */
class ExcelExportStrategyTest {

    private final ExcelExportStrategy strategy = new ExcelExportStrategy();

    private ReportDataContext context() {
        return new ReportDataContext(
                "BÁO CÁO CHỐT CA",
                "Ngày 01/01/2026",
                null,
                Map.of("submittedBy", "1"),
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
    @DisplayName("Xuất .xlsx gồm sheet chính + sheet BẢNG PHỤ và nhúng logo từ classpath")
    void export_producesTwoSheetsAndEmbeddsLogo() throws Exception {
        byte[] bytes = strategy.export(context());

        assertThat(bytes).isNotEmpty();

        try (XSSFWorkbook workbook = (XSSFWorkbook) WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("Báo cáo");
            assertThat(workbook.getSheetAt(1).getSheetName()).isEqualTo("BẢNG PHỤ");
            assertThat(workbook.getAllPictures()).as("logo placeholder classpath được nhúng").isNotEmpty();
        }
    }

    @Test
    @DisplayName("Không có secondaryData vẫn xuất được workbook hợp lệ với 1 sheet")
    void export_withoutSecondaryData_stillProducesWorkbook() throws Exception {
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
        assertThat(bytes).isNotEmpty();

        try (XSSFWorkbook workbook = (XSSFWorkbook) WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            assertThat(workbook.getAllPictures()).isNotEmpty();
        }
    }
}