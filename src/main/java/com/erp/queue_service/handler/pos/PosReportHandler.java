package com.erp.queue_service.handler.pos;

import com.erp.core.constants.ReportExportConstants;
import com.erp.core.domain.Branch;
import com.erp.core.domain.Order;
import com.erp.core.domain.OrderItem;
import com.erp.core.enums.ReportType;
import com.erp.core.report.ReportColumnDefinition;
import com.erp.core.report.ReportDataContext;
import com.erp.queue_service.handler.ModuleReportHandler;
import com.erp.queue_service.messaging.ReportMessage;
import com.erp.queue_service.repository.BranchRepository;
import com.erp.queue_service.repository.OrderItemRepository;
import com.erp.queue_service.repository.OrderRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Handler trích xuất dữ liệu báo cáo phân hệ POS trong queue-service.
 *
 * <p>Hỗ trợ 2 mẫu báo cáo theo hợp đồng {@link ReportExportConstants}:
 * <ul>
 *   <li>{@code POS_ORDER_EXPORT} — danh sách chi tiết đơn hàng theo bộ lọc.</li>
 *   <li>{@code POS_SALES_SUMMARY} — tổng hợp doanh thu theo sản phẩm/biến thể
 *       kèm giá vốn (COGS), lãi gộp và bảng phân bổ kênh thanh toán.</li>
 * </ul>
 *
 * <p>Để tương thích cả mã chuẩn lẫn tên của tài liệu thiết kế
 * ({@code ORDER_LIST}/{@code SALES_SUMMARY}), mọi phân nhánh đều chuẩn hoá qua
 * {@link ReportType}.</p>
 */
@Component
public class PosReportHandler implements ModuleReportHandler {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final BranchRepository branchRepository;

    public PosReportHandler(OrderRepository orderRepository,
                            OrderItemRepository orderItemRepository,
                            BranchRepository branchRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
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

    private Specification<Order> buildOrderSpec(Map<String, Object> params, UUID branchId) {
        String orderType = (String) params.get("orderType");
        String status = (String) params.get("status");
        String fromDateStr = (String) params.get("fromDate");
        String toDateStr = (String) params.get("toDate");

        Instant fromInstant = fromDateStr != null
                ? LocalDate.parse(fromDateStr).atStartOfDay(ZoneId.systemDefault()).toInstant()
                : null;
        Instant toInstant = toDateStr != null
                ? LocalDate.parse(toDateStr).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
                : null;

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (branchId != null) {
                predicates.add(cb.equal(root.get("branchId"), branchId));
            }
            if (orderType != null && !orderType.isBlank()) {
                predicates.add(cb.equal(root.get("orderType"), orderType));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (fromInstant != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), fromInstant));
            }
            if (toInstant != null) {
                predicates.add(cb.lessThan(root.<Instant>get("createdAt"), toInstant));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static String paymentChannelOf(Order o) {
        return o.getPaymentMethod() != null && !o.getPaymentMethod().isBlank() ? o.getPaymentMethod() : "-";
    }

    private Map<UUID, Branch> loadBranchMap(List<Order> orders) {
        Set<UUID> branchIds = orders.stream().map(Order::getBranchId).collect(Collectors.toSet());
        if (branchIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return branchRepository.findAllById(branchIds).stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity()));
    }

    private String buildSubtitle(Map<String, Object> params, UUID branchId, Map<UUID, Branch> branchMap) {
        StringBuilder subtitle = new StringBuilder("Thời gian kết xuất: ").append(LocalDate.now());
        if (params.get("fromDate") != null || params.get("toDate") != null) {
            subtitle.append(" | Từ ngày: ").append(params.get("fromDate"))
                    .append(" Đến ngày: ").append(params.get("toDate"));
        }
        if (branchId != null && branchMap.containsKey(branchId)) {
            subtitle.append(" | Chi nhánh: ").append(branchMap.get(branchId).getName());
        }
        return subtitle.toString();
    }

    // ==== MẪU POS_ORDER_EXPORT: danh sách chi tiết đơn hàng ====

    private ReportDataContext generateOrderListData(ReportMessage message) {
        Map<String, Object> params = message.getParams() != null ? message.getParams() : Collections.emptyMap();
        UUID branchId = message.getBranchId();

        List<Order> orders = orderRepository.findAll(buildOrderSpec(params, branchId));
        Map<UUID, Branch> branchMap = loadBranchMap(orders);

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

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Order o : orders) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderCode", o.getOrderCode());
            LocalDateTime ldt = o.getCreatedAt() != null
                    ? LocalDateTime.ofInstant(o.getCreatedAt(), ZoneId.systemDefault())
                    : null;
            row.put("createdAt", ldt);
            Branch b = branchMap.get(o.getBranchId());
            row.put("branchName", b != null ? b.getName() : o.getBranchId().toString());
            row.put("customerName", o.getCustomerName());
            row.put("customerPhone", o.getCustomerPhone());
            row.put("orderType", o.getOrderType());
            row.put("status", o.getStatus());
            row.put("paymentMethod", paymentChannelOf(o));
            row.put("paymentStatus", o.getPaymentStatus());
            row.put("subtotalAmount", o.getSubtotalAmount());
            row.put("discountAmount", o.getDiscountAmount());
            row.put("deliveryFee", o.getDeliveryFee());
            row.put("totalAmount", o.getTotalAmount());
            rows.add(row);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("label", "TỔNG CỘNG");
        summary.put("orderCount", orders.size());
        summary.put("subtotalAmount", sum(orders, o -> o.getSubtotalAmount()));
        summary.put("discountAmount", sum(orders, o -> o.getDiscountAmount()));
        summary.put("deliveryFee", sum(orders, o -> o.getDeliveryFee()));
        summary.put("totalAmount", sum(orders, o -> o.getTotalAmount()));

        return new ReportDataContext(
                "BÁO CÁO CHI TIẾT ĐƠN HÀNG POS",
                buildSubtitle(params, branchId, branchMap),
                null,
                Map.of("rowCount", orders.size()),
                columns,
                rows,
                summary,
                paymentMethodBreakdown(orders)
        );
    }

    // ==== MẪU POS_SALES_SUMMARY: tổng hợp doanh thu theo sản phẩm/biến thể ====

    private ReportDataContext generateSalesSummaryData(ReportMessage message) {
        Map<String, Object> params = message.getParams() != null ? message.getParams() : Collections.emptyMap();
        UUID branchId = message.getBranchId();

        List<Order> orders = orderRepository.findAll(buildOrderSpec(params, branchId));
        Map<UUID, Branch> branchMap = loadBranchMap(orders);

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

        List<OrderItem> orderItems = loadActiveItems(orders);
        List<Map<String, Object>> rows = buildProductSummaryRows(orderItems);

        return new ReportDataContext(
                "BÁO CÁO TỔNG HỢP DOANH THU POS",
                buildSubtitle(params, branchId, branchMap),
                null,
                Map.of(
                        "rowCount", rows.size(),
                        "orderCount", orders.size(),
                        "itemCount", orderItems.size()
                ),
                columns,
                rows,
                buildProductSummaryTotal(orders, orderItems),
                paymentMethodBreakdown(orders)
        );
    }

    /** Nạp chi tiết sản phẩm hợp lệ (ACTIVE) của toàn bộ đơn đã lọc — tránh truy vấn N+1. */
    private List<OrderItem> loadActiveItems(List<Order> orders) {
        if (orders.isEmpty()) {
            return Collections.emptyList();
        }
        List<UUID> orderIds = orders.stream().map(Order::getId).collect(Collectors.toList());
        return orderItemRepository.findByOrderIdIn(orderIds).stream()
                .filter(item -> item.getStatus() == null || "ACTIVE".equalsIgnoreCase(item.getStatus()))
                .toList();
    }

    /** Gộp doanh thu/COGS/lãi gộp theo từng sản phẩm + biến thể. */
    private List<Map<String, Object>> buildProductSummaryRows(List<OrderItem> orderItems) {
        Map<String, List<OrderItem>> grouped = orderItems.stream()
                .collect(Collectors.groupingBy(PosReportHandler::productKey,
                        LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> rows = new ArrayList<>();
        grouped.forEach((key, items) -> {
            int quantity = items.stream().mapToInt(PosReportHandler::quantityOf).sum();
            BigDecimal revenue = sumAmount(items, OrderItem::getTotalPrice);
            BigDecimal cogs = sumAmount(items, PosReportHandler::itemCogs);
            BigDecimal grossProfit = revenue.subtract(cogs);
            BigDecimal unitPrice = revenue.divide(BigDecimal.valueOf(Math.max(quantity, 1)),
                    2, RoundingMode.HALF_UP);
            BigDecimal profitMargin = revenue.signum() != 0
                    ? grossProfit.multiply(BigDecimal.valueOf(100)).divide(revenue, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            OrderItem first = items.get(0);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productCode", first.getProductCode());
            row.put("productName", first.getProductName());
            row.put("variantName", first.getVariantName() != null ? first.getVariantName() : "");
            row.put("quantity", quantity);
            row.put("unitPrice", unitPrice);
            row.put("revenue", revenue);
            row.put("cogs", cogs);
            row.put("grossProfit", grossProfit);
            row.put("profitMargin", profitMargin.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%");
            rows.add(row);
        });
        return rows;
    }

    /** Dòng tổng cộng của báo cáo tổng hợp doanh thu theo sản phẩm. */
    private Map<String, Object> buildProductSummaryTotal(List<Order> orders, List<OrderItem> orderItems) {
        BigDecimal revenue = sumAmount(orderItems, OrderItem::getTotalPrice);
        BigDecimal cogs = sumAmount(orderItems, PosReportHandler::itemCogs);
        BigDecimal grossProfit = revenue.subtract(cogs);
        BigDecimal profitMargin = revenue.signum() != 0
                ? grossProfit.multiply(BigDecimal.valueOf(100)).divide(revenue, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("label", "TỔNG CỘNG");
        summary.put("orderCount", orders.size());
        summary.put("quantity", orderItems.stream().mapToInt(PosReportHandler::quantityOf).sum());
        summary.put("revenue", revenue);
        summary.put("cogs", cogs);
        summary.put("grossProfit", grossProfit);
        summary.put("profitMargin", profitMargin);
        return summary;
    }

    private static BigDecimal itemCogs(OrderItem item) {
        if (item.getUnitCogsAmount() == null || item.getQuantity() == null) {
            return BigDecimal.ZERO;
        }
        return item.getUnitCogsAmount().multiply(BigDecimal.valueOf(item.getQuantity()));
    }

    private static int quantityOf(OrderItem item) {
        return item.getQuantity() != null ? item.getQuantity() : 0;
    }

    private static String productKey(OrderItem item) {
        return (item.getProductCode() == null ? "" : item.getProductCode())
                + "|"
                + (item.getVariantName() == null ? "" : item.getVariantName());
    }

    private BigDecimal sumAmount(List<OrderItem> items, Function<OrderItem, BigDecimal> extractor) {
        return items.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ==== HELPERS ====

    private java.math.BigDecimal sum(List<Order> orders, Function<Order, java.math.BigDecimal> extractor) {
        return orders.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }

    /** Bảng phụ phân bổ doanh thu theo phương thức thanh toán (Sheet 2). */
    private List<Map<String, Object>> paymentMethodBreakdown(List<Order> orders) {
        Map<String, Long> counts = orders.stream()
                .collect(Collectors.groupingBy(PosReportHandler::paymentChannelOf, Collectors.counting()));
        Map<String, java.math.BigDecimal> amounts = orders.stream()
                .collect(Collectors.groupingBy(PosReportHandler::paymentChannelOf,
                        Collectors.reducing(java.math.BigDecimal.ZERO,
                                o -> o.getTotalAmount() != null ? o.getTotalAmount() : java.math.BigDecimal.ZERO,
                                java.math.BigDecimal::add)));

        List<Map<String, Object>> rows = new ArrayList<>();
        counts.keySet().stream().sorted().forEach(channel -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("channel", channel);
            row.put("orderCount", counts.get(channel));
            row.put("amount", amounts.getOrDefault(channel, java.math.BigDecimal.ZERO));
            rows.add(row);
        });
        return rows;
    }
}