package com.erp.queue_service.handler.pos;

import com.erp.core.domain.Branch;
import com.erp.core.domain.Order;
import com.erp.queue_service.export.ReportColumnDefinition;
import com.erp.queue_service.export.ReportDataContext;
import com.erp.queue_service.handler.ModuleReportHandler;
import com.erp.queue_service.messaging.ReportMessage;
import com.erp.queue_service.repository.BranchRepository;
import com.erp.queue_service.repository.OrderRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Handler trích xuất dữ liệu báo cáo đơn hàng POS trong queue-service.
 */
@Component
public class PosReportHandler implements ModuleReportHandler {

    private final OrderRepository orderRepository;
    private final BranchRepository branchRepository;

    public PosReportHandler(OrderRepository orderRepository, BranchRepository branchRepository) {
        this.orderRepository = orderRepository;
        this.branchRepository = branchRepository;
    }

    @Override
    public boolean supports(String module) {
        return "POS".equalsIgnoreCase(module);
    }

    @Override
    public String getBaseFileName(ReportMessage message) {
        return "BaoCaoDonHangPOS";
    }

    @Override
    public ReportDataContext generateReportData(ReportMessage message) {
        Map<String, Object> params = message.getParams() != null ? message.getParams() : Collections.emptyMap();
        UUID branchId = message.getBranchId();

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

        Specification<Order> spec = (root, query, cb) -> {
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

        List<Order> orders = orderRepository.findAll(spec);
        Set<UUID> branchIds = orders.stream().map(Order::getBranchId).collect(Collectors.toSet());
        Map<UUID, Branch> branchMap = branchRepository.findAllById(branchIds).stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity()));

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
            row.put("paymentMethod", o.getPaymentMethod() != null ? o.getPaymentMethod() : "-");
            row.put("paymentStatus", o.getPaymentStatus());
            row.put("subtotalAmount", o.getSubtotalAmount());
            row.put("discountAmount", o.getDiscountAmount());
            row.put("deliveryFee", o.getDeliveryFee());
            row.put("totalAmount", o.getTotalAmount());
            rows.add(row);
        }

        String subtitle = "Thời gian kết xuất: " + LocalDate.now();
        return new ReportDataContext("BÁO CÁO CHI TIẾT ĐƠN HÀNG POS", subtitle, columns, rows);
    }
}
