package com.erp.queue_service.handler.pos;

import com.erp.core.domain.Branch;
import com.erp.core.enums.ReportType;
import com.erp.core.report.ReportColumnDefinition;
import com.erp.core.report.ReportDataContext;
import com.erp.core.dto.report.pos.OrderExportDto;
import com.erp.core.dto.report.pos.ProductSalesSummaryDto;
import com.erp.core.report.LazyDtoRowList;
import com.erp.queue_service.handler.ModuleReportHandler;
import com.erp.queue_service.messaging.ReportMessage;
import com.erp.queue_service.repository.BranchRepository;
import com.erp.queue_service.repository.PosReportCriteria;
import com.erp.queue_service.repository.PosReportQueryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Handler trích xuất dữ liệu báo cáo phân hệ POS trong queue-service.
 *
 * <p>Được thiết kế tối ưu hóa bộ nhớ RAM với:
 * <ul>
 *   <li>Truy vấn phân trang theo lô (Slice 2.000 dòng) và DTO projection để bypass Hibernate PersistenceContext.</li>
 *   <li>Lazy row transformation ({@link LazyDtoRowList}) không giữ Map trong heap.</li>
 *   <li>Tổng hợp doanh thu sản phẩm trực tiếp từ DB qua GROUP BY thay vì tải toàn bộ OrderItem vào RAM.</li>
 * </ul>
 * </p>
 */
@Component
public class PosReportHandler implements ModuleReportHandler {

    private static final Logger log = LoggerFactory.getLogger(PosReportHandler.class);
    private static final int BATCH_SIZE = 2000;

    private final PosReportQueryRepository reportQueryRepository;
    private final BranchRepository branchRepository;

    public PosReportHandler(PosReportQueryRepository reportQueryRepository,
                            BranchRepository branchRepository) {
        this.reportQueryRepository = reportQueryRepository;
        this.branchRepository = branchRepository;
    }

    @Override
    public boolean supports(String module) {
        return "POS".equalsIgnoreCase(module);
    }

    @Override
    public String getBaseFileName(ReportMessage message) {
        if (ReportType.from(message.getReportType()) == ReportType.POS_SALES_SUMMARY) {
            return "BaoCaoTongHopDoanhThuPOS";
        }
        return "BaoCaoChiTietDonHangPOS";
    }

    @Override
    public ReportDataContext generateReportData(ReportMessage message) {
        if (ReportType.from(message.getReportType()) == ReportType.POS_SALES_SUMMARY) {
            return generateSalesSummaryData(message);
        }
        return generateOrderListData(message);
    }

    private String buildSubtitle(Map<String, Object> params, UUID branchId) {
        StringBuilder subtitle = new StringBuilder("Thời gian kết xuất: ").append(LocalDate.now());
        if (params.get("fromDate") != null || params.get("toDate") != null) {
            subtitle.append(" | Từ ngày: ").append(params.get("fromDate"))
                    .append(" Đến ngày: ").append(params.get("toDate"));
        }
        if (branchId != null) {
            String branchName = branchRepository.findById(branchId)
                    .map(Branch::getName)
                    .orElse(branchId.toString());
            subtitle.append(" | Chi nhánh: ").append(branchName);
        }
        return subtitle.toString();
    }

    // ==== MẪU POS_ORDER_EXPORT: danh sách chi tiết đơn hàng (Streaming Chunking) ====

    private ReportDataContext generateOrderListData(ReportMessage message) {
        Map<String, Object> params = message.getParams() != null ? message.getParams() : Collections.emptyMap();
        UUID branchId = message.getBranchId();
        PosReportCriteria criteria = buildCriteria(params, branchId);

        List<ReportColumnDefinition> columns = List.of(
                ReportColumnDefinition.text("orderCode", "Mã đơn", 14),
                ReportColumnDefinition.dateTime("createdAt", "Thời gian đặt", 16),
                ReportColumnDefinition.text("branchName", "Chi nhánh", 22),
                ReportColumnDefinition.text("customerName", "Khách hàng", 18),
                ReportColumnDefinition.text("customerPhone", "SĐT", 12),
                ReportColumnDefinition.text("orderType", "Loại đơn", 10),
                ReportColumnDefinition.text("status", "Trạng thái", 12),
                ReportColumnDefinition.text("paymentMethod", "PT thanh toán", 14),
                ReportColumnDefinition.text("paymentStatus", "Trạng thái TT", 14),
                ReportColumnDefinition.currency("subtotalAmount", "Tạm tính", 14),
                ReportColumnDefinition.currency("discountAmount", "Giảm giá", 12),
                ReportColumnDefinition.currency("deliveryFee", "Phí giao", 12),
                ReportColumnDefinition.currency("totalAmount", "Tổng tiền", 16)
        );

        log.info("[PosReport] Bắt đầu trích xuất đơn hàng theo lô (batchSize={}) cho Job ID: {}",
                BATCH_SIZE, message.getJobId());

        List<OrderExportDto> allDtos = new ArrayList<>();
        BigDecimal sumSubtotal = BigDecimal.ZERO;
        BigDecimal sumDiscount = BigDecimal.ZERO;
        BigDecimal sumDeliveryFee = BigDecimal.ZERO;
        BigDecimal sumTotalAmount = BigDecimal.ZERO;

        Map<String, Long> paymentCounts = new LinkedHashMap<>();
        Map<String, BigDecimal> paymentAmounts = new LinkedHashMap<>();

        int pageIndex = 0;
        Slice<OrderExportDto> slice;
        long startTime = System.currentTimeMillis();

        do {
            Pageable pageable = PageRequest.of(pageIndex, BATCH_SIZE);
            slice = reportQueryRepository.findOrderExportSlice(criteria, pageable);
            List<OrderExportDto> batch = slice.getContent();
            allDtos.addAll(batch);

            for (OrderExportDto dto : batch) {
                if (dto.subtotalAmount() != null) sumSubtotal = sumSubtotal.add(dto.subtotalAmount());
                if (dto.discountAmount() != null) sumDiscount = sumDiscount.add(dto.discountAmount());
                if (dto.deliveryFee() != null) sumDeliveryFee = sumDeliveryFee.add(dto.deliveryFee());
                if (dto.totalAmount() != null) sumTotalAmount = sumTotalAmount.add(dto.totalAmount());

                String channel = (dto.paymentMethod() != null && !dto.paymentMethod().isBlank()) ? dto.paymentMethod() : "-";
                paymentCounts.merge(channel, 1L, Long::sum);
                paymentAmounts.merge(channel, dto.totalAmount() != null ? dto.totalAmount() : BigDecimal.ZERO, BigDecimal::add);
            }

            pageIndex++;
            if (pageIndex % 5 == 0 || !slice.hasNext()) {
                long elapsed = System.currentTimeMillis() - startTime;
                long freeMem = Runtime.getRuntime().freeMemory() / (1024 * 1024);
                long totalMem = Runtime.getRuntime().totalMemory() / (1024 * 1024);
                log.info("[PosReport] Lô {}: Đã tải {}, dòng (tổng {}, dòng) | RAM Heap used: {}MB / {}MB | Thời gian: {}s",
                        pageIndex, batch.size(), allDtos.size(), (totalMem - freeMem), totalMem, String.format("%.2f", elapsed / 1000.0));
            }
        } while (slice.hasNext());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("label", "TỔNG CỘNG");
        summary.put("orderCount", allDtos.size());
        summary.put("subtotalAmount", sumSubtotal);
        summary.put("discountAmount", sumDiscount);
        summary.put("deliveryFee", sumDeliveryFee);
        summary.put("totalAmount", sumTotalAmount);

        List<Map<String, Object>> paymentBreakdownRows = new ArrayList<>();
        paymentCounts.keySet().stream().sorted().forEach(channel -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("channel", channel);
            row.put("orderCount", paymentCounts.get(channel));
            row.put("amount", paymentAmounts.getOrDefault(channel, BigDecimal.ZERO));
            paymentBreakdownRows.add(row);
        });

        // Sử dụng LazyDtoRowList để chuyển đổi DTO sang Map on-demand khi render
        List<Map<String, Object>> lazyRows = new LazyDtoRowList<>(allDtos, OrderExportDto::toRowMap);

        return new ReportDataContext(
                "BÁO CÁO CHI TIẾT ĐƠN HÀNG POS",
                buildSubtitle(params, branchId),
                null,
                Map.of("rowCount", allDtos.size()),
                columns,
                lazyRows,
                summary,
                paymentBreakdownRows
        );
    }

    // ==== MẪU POS_SALES_SUMMARY: tổng hợp doanh thu theo sản phẩm/biến thể ====

    private ReportDataContext generateSalesSummaryData(ReportMessage message) {
        Map<String, Object> params = message.getParams() != null ? message.getParams() : Collections.emptyMap();
        UUID branchId = message.getBranchId();
        PosReportCriteria criteria = buildCriteria(params, branchId);

        List<ReportColumnDefinition> columns = List.of(
                ReportColumnDefinition.text("productCode", "Mã SP", 14),
                ReportColumnDefinition.text("productName", "Sản phẩm", 24),
                ReportColumnDefinition.text("variantName", "Biến thể", 16),
                ReportColumnDefinition.number("quantity", "SL", 10),
                ReportColumnDefinition.currency("unitPrice", "Giá bán", 14),
                ReportColumnDefinition.currency("revenue", "Doanh thu", 16),
                ReportColumnDefinition.currency("cogs", "Giá vốn (COGS)", 16),
                ReportColumnDefinition.currency("grossProfit", "Lãi gộp", 16),
                ReportColumnDefinition.text("profitMargin", "Biên LN %", 12)
        );

        log.info("[PosReport] Truy vấn tổng hợp doanh thu sản phẩm trực tiếp từ DB cho Job ID: {}", message.getJobId());

        // Tổng hợp trực tiếp tại DB qua GROUP BY
        List<ProductSalesSummaryDto> productSummaries = reportQueryRepository.summarizeSalesByProduct(criteria);

        long totalQuantity = 0;
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalCogs = BigDecimal.ZERO;

        for (ProductSalesSummaryDto p : productSummaries) {
            if (p.totalQuantity() != null) totalQuantity += p.totalQuantity();
            if (p.totalRevenue() != null) totalRevenue = totalRevenue.add(p.totalRevenue());
            if (p.totalCogs() != null) totalCogs = totalCogs.add(p.totalCogs());
        }

        BigDecimal totalGrossProfit = totalRevenue.subtract(totalCogs);
        BigDecimal totalMargin = totalRevenue.signum() != 0
                ? totalGrossProfit.multiply(BigDecimal.valueOf(100)).divide(totalRevenue, 2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("label", "TỔNG CỘNG");
        summary.put("quantity", totalQuantity);
        summary.put("revenue", totalRevenue);
        summary.put("cogs", totalCogs);
        summary.put("grossProfit", totalGrossProfit);
        summary.put("profitMargin", totalMargin.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString() + "%");

        List<Map<String, Object>> lazyRows = new LazyDtoRowList<>(productSummaries, ProductSalesSummaryDto::toRowMap);

        return new ReportDataContext(
                "BÁO CÁO TỔNG HỢP DOANH THU POS",
                buildSubtitle(params, branchId),
                null,
                Map.of("rowCount", productSummaries.size()),
                columns,
                lazyRows,
                summary,
                Collections.emptyList()
        );
    }

    private PosReportCriteria buildCriteria(Map<String, Object> params, UUID branchId) {
        String fromDate = optionalString(params, "fromDate");
        String toDate = optionalString(params, "toDate");
        ZoneId reportZone = ZoneId.systemDefault();

        Instant fromInstant = fromDate == null
                ? null
                : LocalDate.parse(fromDate).atStartOfDay(reportZone).toInstant();
        Instant toInstant = toDate == null
                ? null
                : LocalDate.parse(toDate).plusDays(1).atStartOfDay(reportZone).toInstant();

        return new PosReportCriteria(
                branchId,
                optionalString(params, "orderType"),
                optionalString(params, "status"),
                fromInstant,
                toInstant
        );
    }

    private static String optionalString(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim();
    }
}
