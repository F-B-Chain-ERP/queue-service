package com.erp.queue_service.export;

import com.erp.core.enums.ExportFormat;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Hiện thực chiến lược xuất file PDF trong queue-service.
 */
@Component
public class PdfExportStrategy implements ExportStrategy {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DecimalFormat CURRENCY_FORMAT = new DecimalFormat("#,##0 ₫");
    private static final DecimalFormat NUMBER_FORMAT = new DecimalFormat("#,##0");

    @Override
    public ExportFormat getSupportedFormat() {
        return ExportFormat.PDF;
    }

    @Override
    public String getContentType() {
        return "application/pdf";
    }

    @Override
    public String getFileExtension() {
        return ".pdf";
    }

    @Override
    public byte[] export(ReportDataContext context) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 20, 20, 25, 25);
            PdfWriter.getInstance(document, out);

            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, new Color(30, 58, 110));
            Font subFont = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, Color.GRAY);
            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
            Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.DARK_GRAY);

            Paragraph titleParagraph = new Paragraph(
                    context.title() != null ? context.title().toUpperCase() : "BÁO CÁO HỆ THỐNG ERP",
                    titleFont
            );
            titleParagraph.setAlignment(Element.ALIGN_LEFT);
            titleParagraph.setSpacingAfter(4);
            document.add(titleParagraph);

            if (context.subtitle() != null && !context.subtitle().isBlank()) {
                Paragraph subParagraph = new Paragraph(context.subtitle(), subFont);
                subParagraph.setAlignment(Element.ALIGN_LEFT);
                subParagraph.setSpacingAfter(12);
                document.add(subParagraph);
            } else {
                titleParagraph.setSpacingAfter(12);
            }

            List<ReportColumnDefinition> columns = context.columns();
            int numCols = columns.size();
            PdfPTable table = new PdfPTable(numCols);
            table.setWidthPercentage(100);

            float[] widths = new float[numCols];
            for (int i = 0; i < numCols; i++) {
                widths[i] = Math.max(columns.get(i).width(), 10);
            }
            table.setWidths(widths);
            table.setHeaderRows(1);

            Color headerBg = new Color(41, 84, 144);
            for (ReportColumnDefinition colDef : columns) {
                PdfPCell headerCell = new PdfPCell(new Phrase(colDef.header(), headerFont));
                headerCell.setBackgroundColor(headerBg);
                headerCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                headerCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                headerCell.setPaddingTop(6);
                headerCell.setPaddingBottom(6);
                headerCell.setBorderColor(new Color(200, 200, 200));
                table.addCell(headerCell);
            }

            List<Map<String, Object>> rows = context.rows();
            Color zebraBg = new Color(248, 250, 252);

            int rowIndex = 0;
            for (Map<String, Object> rowData : rows) {
                boolean isZebra = (rowIndex % 2 == 1);
                rowIndex++;

                for (ReportColumnDefinition colDef : columns) {
                    Object val = rowData.get(colDef.key());
                    String textValue = "-";
                    int align = Element.ALIGN_LEFT;

                    if (val != null) {
                        switch (colDef.type()) {
                            case CURRENCY -> {
                                if (val instanceof Number num) {
                                    textValue = CURRENCY_FORMAT.format(num);
                                } else {
                                    textValue = CURRENCY_FORMAT.format(Double.parseDouble(val.toString()));
                                }
                                align = Element.ALIGN_RIGHT;
                            }
                            case NUMBER -> {
                                if (val instanceof Number num) {
                                    textValue = NUMBER_FORMAT.format(num);
                                } else {
                                    textValue = NUMBER_FORMAT.format(Double.parseDouble(val.toString()));
                                }
                                align = Element.ALIGN_RIGHT;
                            }
                            case DATE -> {
                                if (val instanceof LocalDate ld) {
                                    textValue = ld.format(DATE_FORMATTER);
                                } else {
                                    textValue = val.toString();
                                }
                                align = Element.ALIGN_CENTER;
                            }
                            case DATETIME -> {
                                if (val instanceof LocalDateTime ldt) {
                                    textValue = ldt.format(DATETIME_FORMATTER);
                                } else {
                                    textValue = val.toString();
                                }
                                align = Element.ALIGN_CENTER;
                            }
                            default -> {
                                textValue = val.toString();
                                align = Element.ALIGN_LEFT;
                            }
                        }
                    }

                    PdfPCell cell = new PdfPCell(new Phrase(textValue, cellFont));
                    cell.setHorizontalAlignment(align);
                    cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                    cell.setPaddingTop(4);
                    cell.setPaddingBottom(4);
                    cell.setPaddingLeft(5);
                    cell.setPaddingRight(5);
                    cell.setBorderColor(new Color(230, 230, 230));
                    if (isZebra) {
                        cell.setBackgroundColor(zebraBg);
                    }
                    table.addCell(cell);
                }
            }

            document.add(table);
            document.close();

            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi xuất tệp PDF: " + e.getMessage(), e);
        }
    }
}
