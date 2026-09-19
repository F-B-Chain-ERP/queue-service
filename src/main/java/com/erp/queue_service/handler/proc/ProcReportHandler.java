package com.erp.queue_service.handler.proc;

import com.erp.core.domain.PurchaseOrder;
import com.erp.core.domain.Supplier;
import com.erp.core.domain.Warehouse;
import com.erp.queue_service.export.ReportColumnDefinition;
import com.erp.queue_service.export.ReportDataContext;
import com.erp.queue_service.handler.ModuleReportHandler;
import com.erp.queue_service.messaging.ReportMessage;
import com.erp.queue_service.repository.PurchaseOrderRepository;
import com.erp.queue_service.repository.SupplierRepository;
import com.erp.queue_service.repository.WarehouseRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Handler trích xuất dữ liệu báo cáo đơn mua hàng (PO) trong queue-service.
 */
@Component
public class ProcReportHandler implements ModuleReportHandler {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SupplierRepository supplierRepository;
    private final WarehouseRepository warehouseRepository;

    public ProcReportHandler(PurchaseOrderRepository purchaseOrderRepository,
                             SupplierRepository supplierRepository,
                             WarehouseRepository warehouseRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.supplierRepository = supplierRepository;
        this.warehouseRepository = warehouseRepository;
    }

    @Override
    public boolean supports(String module) {
        return "PROC".equalsIgnoreCase(module);
    }

    @Override
    public String getBaseFileName(ReportMessage message) {
        return "BaoCaoDonMuaHang";
    }

    @Override
    public ReportDataContext generateReportData(ReportMessage message) {
        Map<String, Object> params = message.getParams() != null ? message.getParams() : Collections.emptyMap();

        String search = (String) params.get("search");
        String status = (String) params.get("status");
        String supplierIdStr = (String) params.get("supplierId");
        String warehouseIdStr = (String) params.get("warehouseId");
        String fromDateStr = (String) params.get("fromDate");
        String toDateStr = (String) params.get("toDate");

        UUID supplierId = supplierIdStr != null ? UUID.fromString(supplierIdStr) : null;
        UUID warehouseId = warehouseIdStr != null ? UUID.fromString(warehouseIdStr) : null;
        LocalDate fromDate = fromDateStr != null ? LocalDate.parse(fromDateStr) : null;
        LocalDate toDate = toDateStr != null ? LocalDate.parse(toDateStr) : null;

        Specification<PurchaseOrder> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(search)) {
                predicates.add(cb.like(cb.lower(root.get("poCode")), "%" + search.trim().toLowerCase() + "%"));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (supplierId != null) {
                predicates.add(cb.equal(root.get("supplierId"), supplierId));
            }
            if (warehouseId != null) {
                predicates.add(cb.equal(root.get("warehouseId"), warehouseId));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("orderDate"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("orderDate"), toDate));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        List<PurchaseOrder> pos = purchaseOrderRepository.findAll(spec);
        Set<UUID> supplierIds = pos.stream().map(PurchaseOrder::getSupplierId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> warehouseIds = pos.stream().map(PurchaseOrder::getWarehouseId).filter(Objects::nonNull).collect(Collectors.toSet());

        Map<UUID, Supplier> supplierMap = supplierRepository.findAllById(supplierIds).stream()
                .collect(Collectors.toMap(Supplier::getId, Function.identity()));
        Map<UUID, Warehouse> warehouseMap = warehouseRepository.findAllById(warehouseIds).stream()
                .collect(Collectors.toMap(Warehouse::getId, Function.identity()));

        List<ReportColumnDefinition> columns = List.of(
                ReportColumnDefinition.text("poCode", "Mã đơn PO", 14),
                ReportColumnDefinition.date("orderDate", "Ngày đặt", 12),
                ReportColumnDefinition.date("expectedDate", "Ngày nhận DK", 14),
                ReportColumnDefinition.text("supplierName", "Nhà cung cấp", 24),
                ReportColumnDefinition.text("warehouseName", "Kho nhận", 20),
                ReportColumnDefinition.text("status", "Trạng thái", 14),
                ReportColumnDefinition.currency("subtotalAmount", "Tiền hàng", 16),
                ReportColumnDefinition.currency("totalAmount", "Tổng tiền PO", 16),
                ReportColumnDefinition.text("note", "Ghi chú", 25)
        );

        List<Map<String, Object>> rows = new ArrayList<>();
        for (PurchaseOrder po : pos) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("poCode", po.getPoCode());
            row.put("orderDate", po.getOrderDate());
            row.put("expectedDate", po.getExpectedDate());
            Supplier s = supplierMap.get(po.getSupplierId());
            row.put("supplierName", s != null ? s.getName() : po.getSupplierId().toString());
            Warehouse w = warehouseMap.get(po.getWarehouseId());
            row.put("warehouseName", w != null ? w.getName() : po.getWarehouseId().toString());
            row.put("status", po.getStatus());
            row.put("subtotalAmount", po.getSubtotalAmount());
            row.put("totalAmount", po.getTotalAmount());
            row.put("note", po.getNote() != null ? po.getNote() : "-");
            rows.add(row);
        }

        String subtitle = "Thời gian kết xuất: " + LocalDate.now();
        return new ReportDataContext("BÁO CÁO TỔNG HỢP ĐƠN MUA HÀNG (PO)", subtitle, columns, rows);
    }
}
