package com.erp.queue_service.export;

import com.erp.core.enums.ExportFormat;
import com.erp.core.report.ReportColumnDefinition;
import com.erp.core.report.ReportDataContext;
import com.erp.core.util.ReportLogoResolver;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chiến lược xuất PDF khổ A4 ngang trong queue-service theo tài liệu thiết kế:
 * logo ≤120pt, zebra, dòng tổng #E2E8F0, bảng phụ, vùng ký 3 cột cho biên bản chốt ca.
 */
@Component
public class PdfExportStrategy implements ExportStrategy {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DecimalFormat CURRENCY_FORMAT = new DecimalFormat("#,##0 ₫");
    private static final DecimalFormat NUMBER_FORMAT = new DecimalFormat("#,##0");

    private static final Color BRAND_COLOR = new Color(30, 58, 138);       // #1E3A8A
    private static final Color ZEBRA_COLOR = new Color(241, 245, 249);     // #F1F5F9
    private static final Color FOOTER_COLOR = new Color(226, 232, 240);    // #E2E8F0

    private static final Set<String> MONEY_KEYS = Set.of(
            "amount", "totalAmount", "subtotalAmount", "discountAmount", "deliveryFee",
            "grossRevenue", "netRevenue", "cashAmount", "transferAmount", "openingCash", "closingCash",
            "totalSales", "cashSales", "cardSales", "bankTransferSales", "ewalletSales",
            "initialCash", "cashPayout", "expectedCash", "actualCash", "difference");

    /** Đường dẫn logo cấu hình {@code app.report.logo-path} (có thể rỗng — resolver sẽ dùng nguồn dự phòng). */
    @Value("${app.report.logo-path:}")
    private String configuredLogoPath;

    private final BaseFont regularBaseFont;
    private final BaseFont boldBaseFont;
    private final BaseFont italicBaseFont;

    public PdfExportStrategy() {
        this.regularBaseFont = loadBaseFont("/font/NotoSans-Regular.ttf", "NotoSans-Regular.ttf");
        this.boldBaseFont = loadBaseFont("/font/NotoSans-Bold.ttf", "NotoSans-Bold.ttf");
        this.italicBaseFont = loadBaseFont("/font/NotoSans-Italic.ttf", "NotoSans-Italic.ttf");
    }

    private static BaseFont loadBaseFont(String resourcePath, String name) {
        try (var is = PdfExportStrategy.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                byte[] fontBytes = is.readAllBytes();
                return BaseFont.createFont(name, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, fontBytes, null);
            }
        } catch (Exception ignored) {
        }
        try {
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new RuntimeException("Lỗi tạo font dự phòng: " + e.getMessage(), e);
        }
    }

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

    public static final int MAX_PDF_ROWS = 5000;

    @Override
    public byte[] export(ReportDataContext context) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            exportToStream(context, out);
            return out.toByteArray();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi xuất tệp PDF: " + e.getMessage(), e);
        }
    }

    @Override
    public void exportToStream(ReportDataContext context, java.io.OutputStream out) {
        if (context.rows() != null && context.rows().size() > MAX_PDF_ROWS) {
            throw new IllegalArgumentException(String.format(
                    "Định dạng PDF chỉ hỗ trợ tối đa %,d dòng để đảm bảo định dạng trang in (yêu cầu hiện tại: %,d dòng). " +
                    "Vui lòng chọn định dạng EXCEL để xuất toàn bộ dữ liệu.",
                    MAX_PDF_ROWS, context.rows().size()));
        }
        try {
            Document document = new Document(PageSize.A4.rotate(), 20, 20, 25, 25);
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = new Font(boldBaseFont, 15, Font.NORMAL, BRAND_COLOR);
            Font subFont = new Font(italicBaseFont, 9, Font.NORMAL, Color.GRAY);
            Font headerFont = new Font(boldBaseFont, 9, Font.NORMAL, Color.WHITE);
            Font cellFont = new Font(regularBaseFont, 8, Font.NORMAL, Color.DARK_GRAY);
            Font boldCellFont = new Font(boldBaseFont, 8, Font.NORMAL, Color.DARK_GRAY);

            byte[] logo = ReportLogoResolver.resolve(configuredLogoPath, context.logoPath());
            if (logo != null) {
                document.add(buildHeaderTable(logo, context, titleFont, subFont));
            } else {
                Paragraph titleParagraph = new Paragraph(
                        context.title() != null ? context.title().toUpperCase() : "BÁO CÁO HỆ THỐNG ERP",
                        titleFont);
                titleParagraph.setAlignment(Element.ALIGN_LEFT);
                titleParagraph.setSpacingAfter(4);
                document.add(titleParagraph);

                if (context.subtitle() != null && !context.subtitle().isBlank()) {
                    Paragraph subParagraph = new Paragraph(context.subtitle(), subFont);
                    subParagraph.setSpacingAfter(12);
                    document.add(subParagraph);
                } else {
                    titleParagraph.setSpacingAfter(12);
                }
            }

            List<ReportColumnDefinition> columns = context.columns();
            document.add(buildDataTable(context, columns, cellFont, boldCellFont, headerFont));

            if (context.secondaryData() != null && !context.secondaryData().isEmpty()) {
                document.add(new Paragraph(" "));
                document.add(buildSecondaryTable(context, headerFont, cellFont));
            }

            if (isSignatureReport(context)) {
                document.add(new Paragraph(" "));
                document.add(buildSignatureBlock(boldCellFont));
            }

            document.close();
            out.flush();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi xuất tệp PDF: " + e.getMessage(), e);
        }
    }

    private PdfPTable buildHeaderTable(byte[] logo, ReportDataContext context, Font titleFont, Font subFont) throws Exception {
        Image image = Image.getInstance(logo);
        image.scaleToFit(120, 50); // Logo ≤ 120pt theo thiết kế

        PdfPCell logoCell = new PdfPCell(image, false);
        logoCell.setBorder(Rectangle.NO_BORDER);
        logoCell.setHorizontalAlignment(Element.ALIGN_LEFT);
        logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        logoCell.setPadding(0);

        Paragraph titleParagraph = new Paragraph(
                context.title() != null ? context.title().toUpperCase() : "BÁO CÁO HỆ THỐNG ERP",
                titleFont);
        titleParagraph.setSpacingAfter(6);

        PdfPCell textCell = new PdfPCell();
        textCell.setBorder(Rectangle.NO_BORDER);
        textCell.addElement(titleParagraph);
        if (context.subtitle() != null && !context.subtitle().isBlank()) {
            textCell.addElement(new Paragraph(context.subtitle(), subFont));
        }

        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        header.setWidths(new float[]{120f, 680f});
        header.setSpacingAfter(10);
        header.addCell(logoCell);
        header.addCell(textCell);
        return header;
    }

    private PdfPTable buildDataTable(ReportDataContext context, List<ReportColumnDefinition> columns,
                                     Font cellFont, Font boldCellFont, Font headerFont) {
        int numCols = columns.size();
        PdfPTable table = new PdfPTable(numCols);
        table.setWidthPercentage(100);

        float[] widths = new float[numCols];
        for (int i = 0; i < numCols; i++) {
            widths[i] = Math.max(columns.get(i).width(), 10);
        }
        table.setWidths(widths);
        table.setHeaderRows(1);

        for (ReportColumnDefinition colDef : columns) {
            PdfPCell headerCell = new PdfPCell(new Phrase(colDef.header(), headerFont));
            headerCell.setBackgroundColor(BRAND_COLOR);
            headerCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            headerCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            headerCell.setPaddingTop(6);
            headerCell.setPaddingBottom(6);
            headerCell.setBorderColor(new Color(200, 200, 200));
            table.addCell(headerCell);
        }

        List<Map<String, Object>> rows = context.rows();
        int rowIndex = 0;
        for (Map<String, Object> rowData : rows) {
            boolean zebra = (rowIndex % 2 == 1);
            rowIndex++;
            for (ReportColumnDefinition colDef : columns) {
                PdfPCell cell = buildCell(colDef, rowData.get(colDef.key()), cellFont, zebra);
                table.addCell(cell);
            }
        }

        if (context.summary() instanceof Map<?, ?> summary) {
            for (int col = 0; col < numCols; col++) {
                ReportColumnDefinition colDef = columns.get(col);
                Object val = summary.get(colDef.key());
                String text;
                if (col == 0) {
                    Object label = summary.get("label");
                    text = label != null ? label.toString() : "-";
                } else if (val instanceof Number num) {
                    text = isCurrencyColumn(colDef) ? CURRENCY_FORMAT.format(num) : NUMBER_FORMAT.format(num);
                } else {
                    text = val != null ? val.toString() : "-";
                }
                PdfPCell cell = new PdfPCell(new Phrase(text, boldCellFont));
                cell.setBackgroundColor(FOOTER_COLOR);
                cell.setHorizontalAlignment(col == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
                cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                cell.setPaddingTop(4);
                cell.setPaddingBottom(4);
                cell.setBorderColor(new Color(200, 200, 200));
                table.addCell(cell);
            }
        }
        return table;
    }

    private PdfPCell buildCell(ReportColumnDefinition colDef, Object val, Font font, boolean zebra) {
        String textValue = "-";
        int align = Element.ALIGN_LEFT;

        if (val != null) {
            switch (colDef.type()) {
                case CURRENCY -> {
                    textValue = CURRENCY_FORMAT.format(toNumber(val));
                    align = Element.ALIGN_RIGHT;
                }
                case NUMBER -> {
                    textValue = NUMBER_FORMAT.format(toNumber(val));
                    align = Element.ALIGN_RIGHT;
                }
                case DATE -> {
                    textValue = val instanceof LocalDate ld
                            ? ld.format(DATE_FORMATTER) : val.toString();
                    align = Element.ALIGN_CENTER;
                }
                case DATETIME -> {
                    textValue = val instanceof LocalDateTime ldt
                            ? ldt.format(DATETIME_FORMATTER) : val.toString();
                    align = Element.ALIGN_CENTER;
                }
                default -> textValue = val.toString();
            }
        }

        PdfPCell cell = new PdfPCell(new Phrase(textValue, font));
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPaddingTop(4);
        cell.setPaddingBottom(4);
        cell.setPaddingLeft(5);
        cell.setPaddingRight(5);
        cell.setBorderColor(new Color(230, 230, 230));
        if (zebra) {
            cell.setBackgroundColor(ZEBRA_COLOR);
        }
        return cell;
    }

    private PdfPTable buildSecondaryTable(ReportDataContext context, Font headerFont, Font cellFont) {
        List<Map<String, Object>> secondary = context.secondaryData();
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> row : secondary) {
            keys.addAll(row.keySet());
        }
        List<String> columns = List.copyOf(keys);

        PdfPTable table = new PdfPTable(columns.size());
        table.setWidthPercentage(80);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);

        for (String key : columns) {
            PdfPCell headerCell = new PdfPCell(new Phrase(key.toUpperCase(), headerFont));
            headerCell.setBackgroundColor(BRAND_COLOR);
            headerCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            headerCell.setPaddingTop(5);
            headerCell.setPaddingBottom(5);
            table.addCell(headerCell);
        }

        int rowIndex = 0;
        for (Map<String, Object> rowData : secondary) {
            boolean zebra = (rowIndex % 2 == 1);
            rowIndex++;
            for (String key : columns) {
                Object val = rowData.get(key);
                String text;
                int align = Element.ALIGN_LEFT;
                if (val == null) {
                    text = "-";
                } else if (val instanceof Number num) {
                    text = isMoneyKey(key) ? CURRENCY_FORMAT.format(num) : NUMBER_FORMAT.format(num);
                    align = Element.ALIGN_RIGHT;
                } else {
                    text = val.toString();
                }
                PdfPCell cell = new PdfPCell(new Phrase(text, cellFont));
                cell.setHorizontalAlignment(align);
                cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                cell.setPaddingTop(3);
                cell.setPaddingBottom(3);
                cell.setBorderColor(new Color(230, 230, 230));
                if (zebra) {
                    cell.setBackgroundColor(ZEBRA_COLOR);
                }
                table.addCell(cell);
            }
        }
        return table;
    }

    private PdfPTable buildSignatureBlock(Font font) {
        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(90);
        table.setSpacingBefore(16);

        String[] roles = {"NGƯỜI LẬP CA", "TRƯỞNG CA BÁN HÀNG", "KẾ TOÁN CỬA HÀNG"};
        for (String role : roles) {
            PdfPCell cell = new PdfPCell();
            cell.setBorderColor(new Color(200, 200, 200));
            cell.setPaddingTop(8);
            cell.setPaddingBottom(28);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setVerticalAlignment(Element.ALIGN_BOTTOM);

            Paragraph roleParagraph = new Paragraph(role, font);
            roleParagraph.setAlignment(Element.ALIGN_CENTER);
            cell.addElement(roleParagraph);
            table.addCell(cell);
        }
        return table;
    }

    private boolean isSignatureReport(ReportDataContext context) {
        if (context.metadata() != null && context.metadata().containsKey("submittedBy")) {
            return true;
        }
        String title = context.title();
        return title != null && title.toUpperCase().contains("CHỐT CA");
    }

    private static boolean isCurrencyColumn(ReportColumnDefinition colDef) {
        return colDef.type() == ReportColumnDefinition.ColumnType.CURRENCY;
    }

    private static boolean isMoneyKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase();
        return MONEY_KEYS.contains(key) || k.endsWith("amount") || k.endsWith("sales") || k.endsWith("revenue");
    }

    private static Number toNumber(Object val) {
        if (val instanceof Number num) {
            return num;
        }
        return Double.parseDouble(val.toString());
    }
}