package com.erp.queue_service.export;

import com.erp.core.enums.ExportFormat;
import com.erp.core.report.ReportColumnDefinition;
import com.erp.core.report.ReportDataContext;
import com.erp.core.util.ReportLogoResolver;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chiến lược xuất Excel (.xlsx) trong queue-service theo chuẩn thẩm mỹ tài liệu thiết kế:
 * logo A1:B3, màu brand #1E3A8A, zebra #F1F5F9, dòng tổng #E2E8F0, Sheet 2 "BẢNG PHỤ".
 */
@Component
public class ExcelExportStrategy implements ExportStrategy {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final XSSFColor BRAND_COLOR = new XSSFColor(new byte[]{(byte) 30, (byte) 58, (byte) 138}, null);       // #1E3A8A
    private static final XSSFColor ZEBRA_COLOR = new XSSFColor(new byte[]{(byte) 241, (byte) 245, (byte) 249}, null);     // #F1F5F9
    private static final XSSFColor FOOTER_COLOR = new XSSFColor(new byte[]{(byte) 226, (byte) 232, (byte) 240}, null);    // #E2E8F0

    private static final Set<String> MONEY_KEYS = Set.of(
            "amount", "totalAmount", "subtotalAmount", "discountAmount", "deliveryFee",
            "grossRevenue", "netRevenue", "cashAmount", "transferAmount", "openingCash", "closingCash",
            "totalSales", "cashSales", "cardSales", "bankTransferSales", "ewalletSales",
            "initialCash", "cashPayout", "expectedCash", "actualCash", "difference");

    /** Đường dẫn logo cấu hình {@code app.report.logo-path} (có thể rỗng — resolver sẽ dùng nguồn dự phòng). */
    @Value("${app.report.logo-path:}")
    private String configuredLogoPath;

    @Override
    public ExportFormat getSupportedFormat() {
        return ExportFormat.EXCEL;
    }

    @Override
    public String getContentType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    @Override
    public String getFileExtension() {
        return ".xlsx";
    }

    @Override
    public byte[] export(ReportDataContext context) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            DataFormat dataFormat = workbook.createDataFormat();
            Styles styles = createStyles(workbook, dataFormat);
            byte[] logo = ReportLogoResolver.resolve(configuredLogoPath, context.logoPath());

            renderMainSheet(workbook, styles, context, logo);
            renderSecondarySheet(workbook, styles, context);

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi xuất tệp Excel: " + e.getMessage(), e);
        }
    }

    private void renderMainSheet(XSSFWorkbook workbook, Styles s, ReportDataContext context, byte[] logo) {
        Sheet sheet = workbook.createSheet("Báo cáo");

        List<ReportColumnDefinition> columns = context.columns();
        int colCount = Math.max(columns.size(), 1);

        if (logo != null) {
            writeLogoHeader(workbook, sheet, s, context, logo, colCount);
        } else {
            Row titleRow = sheet.createRow(0);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(context.title() != null ? context.title().toUpperCase() : "BÁO CÁO HỆ THỐNG ERP");
            titleCell.setCellStyle(s.title);
            merge(sheet, 0, 0, 0, colCount - 1);

            if (context.subtitle() != null && !context.subtitle().isBlank()) {
                Row subRow = sheet.createRow(1);
                Cell subCell = subRow.createCell(0);
                subCell.setCellValue(context.subtitle());
                subCell.setCellStyle(s.subtitle);
                merge(sheet, 1, 1, 0, colCount - 1);
            }
        }

        int headerRowIndex = logo != null ? 4 : (context.subtitle() != null && !context.subtitle().isBlank() ? 3 : 2);
        writeColumnHeaders(sheet, s, columns, headerRowIndex);

        int rowIndex = headerRowIndex + 1;
        List<Map<String, Object>> rows = context.rows();
        for (int i = 0; i < rows.size(); i++) {
            writeDataRow(sheet, s, columns, rows.get(i), rowIndex++, i);
        }

        if (context.summary() instanceof Map<?, ?> summary) {
            writeSummaryRow(sheet, s, columns, summary, rowIndex++);
        }

        // Freeze panes dưới header để khi cuộn trang tiêu đề vẫn cố định
        sheet.createFreezePane(0, headerRowIndex + 1);

        // Auto-filter trên dải cột dữ liệu
        if (!rows.isEmpty()) {
            int lastDataRow = (context.summary() instanceof Map<?, ?>) ? rowIndex - 2 : rowIndex - 1;
            sheet.setAutoFilter(new CellRangeAddress(headerRowIndex, lastDataRow, 0, colCount - 1));
        }

        for (int col = 0; col < columns.size(); col++) {
            int userWidth = columns.get(col).width();
            if (userWidth > 0) {
                sheet.setColumnWidth(col, userWidth * 256);
            } else {
                sheet.autoSizeColumn(col);
                sheet.setColumnWidth(col, Math.max(sheet.getColumnWidth(col) + 1200, 3500));
            }
        }
    }

    private void writeLogoHeader(XSSFWorkbook workbook, Sheet sheet, Styles s,
                                 ReportDataContext context, byte[] logo, int colCount) {
        for (int r = 0; r < 3; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                row = sheet.createRow(r);
            }
            row.setHeightInPoints(28);
        }
        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 5 * 256);

        merge(sheet, 0, 2, 0, 1);
        int pictureIdx = workbook.addPicture(logo, detectImageType(logo));
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        CreationHelper helper = workbook.getCreationHelper();
        ClientAnchor anchor = helper.createClientAnchor();
        anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);
        anchor.setCol1(0);
        anchor.setRow1(0);
        anchor.setCol2(2);
        anchor.setRow2(3);
        drawing.createPicture(anchor, pictureIdx);

        Row titleRow = sheet.getRow(0);
        Cell titleCell = titleRow.createCell(2);
        titleCell.setCellValue(context.title() != null ? context.title().toUpperCase() : "BÁO CÁO HỆ THỐNG ERP");
        titleCell.setCellStyle(s.title);
        merge(sheet, 0, 0, 2, Math.max(colCount - 1, 2));

        if (context.subtitle() != null && !context.subtitle().isBlank()) {
            Row subRow = sheet.getRow(1);
            Cell subCell = subRow.createCell(2);
            subCell.setCellValue(context.subtitle());
            subCell.setCellStyle(s.subtitle);
            merge(sheet, 1, 1, 2, Math.max(colCount - 1, 2));
        }
    }

    private void writeColumnHeaders(Sheet sheet, Styles s, List<ReportColumnDefinition> columns, int rowIndex) {
        Row headerRow = sheet.createRow(rowIndex);
        headerRow.setHeightInPoints(26);
        for (int col = 0; col < columns.size(); col++) {
            Cell cell = headerRow.createCell(col);
            cell.setCellValue(columns.get(col).header());
            cell.setCellStyle(s.header);
        }
    }

    private void writeDataRow(Sheet sheet, Styles s, List<ReportColumnDefinition> columns,
                              Map<String, Object> rowData, int rowIndex, int dataIndex) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(20);
        boolean zebra = dataIndex % 2 == 1;
        for (int col = 0; col < columns.size(); col++) {
            ReportColumnDefinition colDef = columns.get(col);
            Cell cell = row.createCell(col);
            Object val = rowData.get(colDef.key());
            setTypedCell(cell, s, colDef.type(), val, zebra);
        }
    }

    private void writeSummaryRow(Sheet sheet, Styles s, List<ReportColumnDefinition> columns,
                                 Map<?, ?> summary, int rowIndex) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(22);
        for (int col = 0; col < columns.size(); col++) {
            Cell cell = row.createCell(col);
            Object val = summary.get(columns.get(col).key());
            if (col == 0) {
                Object label = summary.get("label");
                cell.setCellValue(label != null ? label.toString() : "-");
            } else if (val != null) {
                cell.setCellValue(val.toString());
            } else {
                cell.setCellValue("-");
            }
            cell.setCellStyle(s.footer);
        }
    }

    private void renderSecondarySheet(XSSFWorkbook workbook, Styles s, ReportDataContext context) {
        List<Map<String, Object>> secondary = context.secondaryData();
        if (secondary == null || secondary.isEmpty()) {
            return;
        }

        Sheet sheet = workbook.createSheet("BẢNG PHỤ");
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> row : secondary) {
            keys.addAll(row.keySet());
        }
        List<String> columns = List.copyOf(keys);

        Row titleRow = sheet.createRow(0);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("BẢNG PHỤ KÈM THEO BÁO CÁO");
        titleCell.setCellStyle(s.title);
        merge(sheet, 0, 0, 0, Math.max(columns.size() - 1, 0));

        Row headerRow = sheet.createRow(2);
        for (int col = 0; col < columns.size(); col++) {
            Cell cell = headerRow.createCell(col);
            cell.setCellValue(columns.get(col).toUpperCase());
            cell.setCellStyle(s.header);
        }

        int rowIndex = 3;
        for (int i = 0; i < secondary.size(); i++) {
            Map<String, Object> rowData = secondary.get(i);
            Row row = sheet.createRow(rowIndex++);
            boolean zebra = i % 2 == 1;
            for (int col = 0; col < columns.size(); col++) {
                String key = columns.get(col);
                Object val = rowData.get(key);
                Cell cell = row.createCell(col);
                setGenericCell(cell, s, key, val, zebra);
            }
        }

        for (int col = 0; col < columns.size(); col++) {
            sheet.autoSizeColumn(col);
            sheet.setColumnWidth(col, Math.max(sheet.getColumnWidth(col) + 1200, 2800));
        }
    }

    private void setTypedCell(Cell cell, Styles s, ReportColumnDefinition.ColumnType type, Object val, boolean zebra) {
        if (val == null) {
            cell.setCellValue("-");
            cell.setCellStyle(zebra ? s.zebraText : s.text);
            return;
        }
        switch (type) {
            case CURRENCY -> {
                cell.setCellValue(toDouble(val));
                cell.setCellStyle(zebra ? s.zebraCurrencyStyle : s.currencyStyle);
            }
            case NUMBER -> {
                cell.setCellValue(toDouble(val));
                cell.setCellStyle(zebra ? s.zebraNumber : s.number);
            }
            case DATE -> {
                cell.setCellValue(val instanceof LocalDate ld ? ld.format(DATE_FORMATTER) : val.toString());
                cell.setCellStyle(zebra ? s.zebraDate : s.date);
            }
            case DATETIME -> {
                cell.setCellValue(val instanceof LocalDateTime ldt ? ldt.format(DATETIME_FORMATTER) : val.toString());
                cell.setCellStyle(zebra ? s.zebraDate : s.date);
            }
            default -> {
                cell.setCellValue(val.toString());
                cell.setCellStyle(zebra ? s.zebraText : s.text);
            }
        }
    }

    private void setGenericCell(Cell cell, Styles s, String key, Object val, boolean zebra) {
        if (val == null) {
            cell.setCellValue("-");
            cell.setCellStyle(zebra ? s.zebraText : s.text);
        } else if (val instanceof Number num) {
            cell.setCellValue(num.doubleValue());
            cell.setCellStyle(zebra
                    ? (isMoneyKey(key) ? s.zebraCurrencyStyle : s.zebraNumber)
                    : (isMoneyKey(key) ? s.currencyStyle : s.number));
        } else if (val instanceof LocalDate ld) {
            cell.setCellValue(ld.format(DATE_FORMATTER));
            cell.setCellStyle(zebra ? s.zebraDate : s.date);
        } else if (val instanceof LocalDateTime ldt) {
            cell.setCellValue(ldt.format(DATETIME_FORMATTER));
            cell.setCellStyle(zebra ? s.zebraDate : s.date);
        } else {
            cell.setCellValue(val.toString());
            cell.setCellStyle(zebra ? s.zebraText : s.text);
        }
    }

    private static boolean isMoneyKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase();
        return MONEY_KEYS.contains(key) || k.endsWith("amount") || k.endsWith("sales") || k.endsWith("revenue");
    }

    private static double toDouble(Object val) {
        if (val instanceof Number num) {
            return num.doubleValue();
        }
        return Double.parseDouble(val.toString());
    }

    private Styles createStyles(XSSFWorkbook wb, DataFormat dataFormat) {
        Styles s = new Styles();

        XSSFFont titleFont = wb.createFont();
        titleFont.setFontName("Calibri");
        titleFont.setFontHeightInPoints((short) 16);
        titleFont.setBold(true);
        titleFont.setColor(BRAND_COLOR);
        s.title = wb.createCellStyle();
        s.title.setFont(titleFont);
        s.title.setAlignment(HorizontalAlignment.LEFT);

        XSSFFont subFont = wb.createFont();
        subFont.setFontName("Calibri");
        subFont.setFontHeightInPoints((short) 10);
        subFont.setItalic(true);
        subFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        s.subtitle = wb.createCellStyle();
        s.subtitle.setFont(subFont);

        XSSFFont headerFont = wb.createFont();
        headerFont.setFontName("Calibri");
        headerFont.setFontHeightInPoints((short) 11);
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        s.header = wb.createCellStyle();
        s.header.setFont(headerFont);
        s.header.setFillForegroundColor(BRAND_COLOR);
        s.header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.header.setAlignment(HorizontalAlignment.CENTER);
        s.header.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(s.header);

        s.text = createDataStyle(wb, HorizontalAlignment.LEFT);
        s.number = createDataStyle(wb, HorizontalAlignment.RIGHT);
        s.number.setDataFormat(dataFormat.getFormat("#,##0"));
        s.currencyStyle = createDataStyle(wb, HorizontalAlignment.RIGHT);
        s.currencyStyle.setDataFormat(dataFormat.getFormat("#,##0 \"₫\""));
        s.date = createDataStyle(wb, HorizontalAlignment.CENTER);

        s.zebraText = cloneWithZebra(wb, s.text);
        s.zebraNumber = cloneWithZebra(wb, s.number);
        s.zebraCurrencyStyle = cloneWithZebra(wb, s.currencyStyle);
        s.zebraDate = cloneWithZebra(wb, s.date);

        XSSFFont footerFont = wb.createFont();
        footerFont.setFontName("Calibri");
        footerFont.setFontHeightInPoints((short) 11);
        footerFont.setBold(true);
        footerFont.setColor(BRAND_COLOR);
        s.footer = wb.createCellStyle();
        s.footer.setFont(footerFont);
        s.footer.setAlignment(HorizontalAlignment.LEFT);
        s.footer.setVerticalAlignment(VerticalAlignment.CENTER);
        s.footer.setFillForegroundColor(FOOTER_COLOR);
        s.footer.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorders(s.footer);

        return s;
    }

    private CellStyle createDataStyle(Workbook wb, HorizontalAlignment align) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setFontName("Calibri");
        font.setFontHeightInPoints((short) 10);
        style.setFont(font);
        style.setAlignment(align);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(style);
        return style;
    }

    private XSSFCellStyle cloneWithZebra(XSSFWorkbook wb, CellStyle base) {
        XSSFCellStyle style = wb.createCellStyle();
        style.cloneStyleFrom(base);
        style.setFillForegroundColor(ZEBRA_COLOR);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private void setBorders(CellStyle style) {
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
    }

    private static void merge(Sheet sheet, int firstRow, int lastRow, int firstCol, int lastCol) {
        if (lastCol <= firstCol || lastRow < firstRow) {
            return;
        }
        sheet.addMergedRegion(new CellRangeAddress(firstRow, lastRow, firstCol, lastCol));
    }

    private static int detectImageType(byte[] img) {
        if (img.length > 3 && img[0] == (byte) 0x89 && img[1] == 0x50 && img[2] == 0x4E && img[3] == 0x47) {
            return Workbook.PICTURE_TYPE_PNG;
        }
        if (img.length > 2 && img[0] == (byte) 0xFF && img[1] == (byte) 0xD8) {
            return Workbook.PICTURE_TYPE_JPEG;
        }
        return Workbook.PICTURE_TYPE_PNG;
    }

    private static final class Styles {
        CellStyle title;
        CellStyle subtitle;
        CellStyle header;
        CellStyle text;
        CellStyle number;
        CellStyle currencyStyle;
        CellStyle date;
        CellStyle zebraText;
        CellStyle zebraNumber;
        CellStyle zebraCurrencyStyle;
        CellStyle zebraDate;
        CellStyle footer;
    }
}