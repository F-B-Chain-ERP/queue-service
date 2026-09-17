package com.erp.queue_service.export;

import com.erp.core.enums.ExportFormat;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Hiện thực chiến lược xuất file Excel (.xlsx) trong queue-service.
 */
@Component
public class ExcelExportStrategy implements ExportStrategy {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

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

            Sheet sheet = workbook.createSheet("Báo cáo");
            sheet.setDisplayGridlines(true);

            DataFormat dataFormat = workbook.createDataFormat();

            XSSFFont titleFont = workbook.createFont();
            titleFont.setFontName("Calibri");
            titleFont.setFontHeightInPoints((short) 16);
            titleFont.setBold(true);
            titleFont.setColor(new XSSFColor(new byte[]{(byte) 30, (byte) 58, (byte) 110}, null));

            CellStyle titleStyle = workbook.createCellStyle();
            titleStyle.setFont(titleFont);

            XSSFFont subFont = workbook.createFont();
            subFont.setFontName("Calibri");
            subFont.setFontHeightInPoints((short) 10);
            subFont.setItalic(true);
            subFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());

            CellStyle subStyle = workbook.createCellStyle();
            subStyle.setFont(subFont);

            XSSFFont headerFont = workbook.createFont();
            headerFont.setFontName("Calibri");
            headerFont.setFontHeightInPoints((short) 11);
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            XSSFCellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 41, (byte) 84, (byte) 144}, null));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorders(headerStyle);

            CellStyle textStyle = createDataStyle(workbook, HorizontalAlignment.LEFT);
            CellStyle numberStyle = createDataStyle(workbook, HorizontalAlignment.RIGHT);
            numberStyle.setDataFormat(dataFormat.getFormat("#,##0"));

            CellStyle currencyStyle = createDataStyle(workbook, HorizontalAlignment.RIGHT);
            currencyStyle.setDataFormat(dataFormat.getFormat("#,##0 \"₫\""));

            CellStyle dateStyle = createDataStyle(workbook, HorizontalAlignment.CENTER);

            int rowIndex = 0;
            Row titleRow = sheet.createRow(rowIndex++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(context.title() != null ? context.title().toUpperCase() : "BÁO CÁO HỆ THỐNG ERP");
            titleCell.setCellStyle(titleStyle);

            if (context.subtitle() != null && !context.subtitle().isBlank()) {
                Row subRow = sheet.createRow(rowIndex++);
                Cell subCell = subRow.createCell(0);
                subCell.setCellValue(context.subtitle());
                subCell.setCellStyle(subStyle);
            }

            rowIndex++;

            List<ReportColumnDefinition> columns = context.columns();
            Row headerRow = sheet.createRow(rowIndex++);
            headerRow.setHeightInPoints(26);

            for (int col = 0; col < columns.size(); col++) {
                ReportColumnDefinition colDef = columns.get(col);
                Cell cell = headerRow.createCell(col);
                cell.setCellValue(colDef.header());
                cell.setCellStyle(headerStyle);
            }

            List<Map<String, Object>> rows = context.rows();
            for (Map<String, Object> rowData : rows) {
                Row row = sheet.createRow(rowIndex++);
                row.setHeightInPoints(20);

                for (int col = 0; col < columns.size(); col++) {
                    ReportColumnDefinition colDef = columns.get(col);
                    Cell cell = row.createCell(col);
                    Object val = rowData.get(colDef.key());

                    if (val == null) {
                        cell.setCellValue("-");
                        cell.setCellStyle(textStyle);
                    } else {
                        switch (colDef.type()) {
                            case CURRENCY -> {
                                if (val instanceof Number num) {
                                    cell.setCellValue(num.doubleValue());
                                } else {
                                    cell.setCellValue(Double.parseDouble(val.toString()));
                                }
                                cell.setCellStyle(currencyStyle);
                            }
                            case NUMBER -> {
                                if (val instanceof Number num) {
                                    cell.setCellValue(num.doubleValue());
                                } else {
                                    cell.setCellValue(Double.parseDouble(val.toString()));
                                }
                                cell.setCellStyle(numberStyle);
                            }
                            case DATE -> {
                                if (val instanceof LocalDate ld) {
                                    cell.setCellValue(ld.format(DATE_FORMATTER));
                                } else {
                                    cell.setCellValue(val.toString());
                                }
                                cell.setCellStyle(dateStyle);
                            }
                            case DATETIME -> {
                                if (val instanceof LocalDateTime ldt) {
                                    cell.setCellValue(ldt.format(DATETIME_FORMATTER));
                                } else {
                                    cell.setCellValue(val.toString());
                                }
                                cell.setCellStyle(dateStyle);
                            }
                            default -> {
                                cell.setCellValue(val.toString());
                                cell.setCellStyle(textStyle);
                            }
                        }
                    }
                }
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

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi xuất tệp Excel: " + e.getMessage(), e);
        }
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
}
