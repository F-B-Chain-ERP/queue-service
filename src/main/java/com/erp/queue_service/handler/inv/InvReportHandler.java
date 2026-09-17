package com.erp.queue_service.handler.inv;

import com.erp.core.domain.Material;
import com.erp.core.domain.MaterialStockBalance;
import com.erp.core.domain.Warehouse;
import com.erp.queue_service.export.ReportColumnDefinition;
import com.erp.queue_service.export.ReportDataContext;
import com.erp.queue_service.handler.ModuleReportHandler;
import com.erp.queue_service.messaging.ReportMessage;
import com.erp.queue_service.repository.MaterialRepository;
import com.erp.queue_service.repository.MaterialStockBalanceRepository;
import com.erp.queue_service.repository.WarehouseRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Handler trích xuất dữ liệu báo cáo tồn kho trong queue-service.
 */
@Component
public class InvReportHandler implements ModuleReportHandler {

    private final MaterialStockBalanceRepository balanceRepository;
    private final MaterialRepository materialRepository;
    private final WarehouseRepository warehouseRepository;

    public InvReportHandler(MaterialStockBalanceRepository balanceRepository,
                            MaterialRepository materialRepository,
                            WarehouseRepository warehouseRepository) {
        this.balanceRepository = balanceRepository;
        this.materialRepository = materialRepository;
        this.warehouseRepository = warehouseRepository;
    }

    @Override
    public boolean supports(String module) {
        return "INV".equalsIgnoreCase(module);
    }

    @Override
    public String getBaseFileName(ReportMessage message) {
        return "BaoCaoTonKho";
    }

    @Override
    public ReportDataContext generateReportData(ReportMessage message) {
        Map<String, Object> params = message.getParams() != null ? message.getParams() : Collections.emptyMap();

        String warehouseIdStr = (String) params.get("warehouseId");
        String materialIdStr = (String) params.get("materialId");

        UUID warehouseId = warehouseIdStr != null ? UUID.fromString(warehouseIdStr) : null;
        UUID materialId = materialIdStr != null ? UUID.fromString(materialIdStr) : null;

        Specification<MaterialStockBalance> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (warehouseId != null) {
                predicates.add(cb.equal(root.get("warehouseId"), warehouseId));
            }
            if (materialId != null) {
                predicates.add(cb.equal(root.get("materialId"), materialId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        List<MaterialStockBalance> balances = balanceRepository.findAll(spec);
        Set<UUID> warehouseIds = balances.stream().map(MaterialStockBalance::getWarehouseId).collect(Collectors.toSet());
        Set<UUID> materialIds = balances.stream().map(MaterialStockBalance::getMaterialId).collect(Collectors.toSet());

        Map<UUID, Warehouse> warehouseMap = warehouseRepository.findAllById(warehouseIds).stream()
                .collect(Collectors.toMap(Warehouse::getId, Function.identity()));
        Map<UUID, Material> materialMap = materialRepository.findAllById(materialIds).stream()
                .collect(Collectors.toMap(Material::getId, Function.identity()));

        List<ReportColumnDefinition> columns = List.of(
                ReportColumnDefinition.text("warehouseName", "Kho hàng", 22),
                ReportColumnDefinition.text("materialCode", "Mã NVL", 14),
                ReportColumnDefinition.text("materialName", "Tên nguyên vật liệu", 26),
                ReportColumnDefinition.number("physicalQuantity", "Tồn thực tế", 14),
                ReportColumnDefinition.number("reservedQuantity", "Giữ chỗ", 12),
                ReportColumnDefinition.number("availableQuantity", "Khả dụng", 14)
        );

        List<Map<String, Object>> rows = new ArrayList<>();
        for (MaterialStockBalance b : balances) {
            Map<String, Object> row = new LinkedHashMap<>();
            Warehouse w = warehouseMap.get(b.getWarehouseId());
            row.put("warehouseName", w != null ? w.getName() : b.getWarehouseId().toString());
            Material m = materialMap.get(b.getMaterialId());
            row.put("materialCode", m != null ? m.getCode() : "-");
            row.put("materialName", m != null ? m.getName() : b.getMaterialId().toString());
            row.put("physicalQuantity", b.getQuantityOnHand());
            row.put("reservedQuantity", b.getQuantityReserved());
            BigDecimal avail = (b.getQuantityOnHand() != null ? b.getQuantityOnHand() : BigDecimal.ZERO)
                    .subtract(b.getQuantityReserved() != null ? b.getQuantityReserved() : BigDecimal.ZERO);
            row.put("availableQuantity", avail);
            rows.add(row);
        }

        String subtitle = "Thời gian kết xuất: " + LocalDate.now();
        return new ReportDataContext("BÁO CÁO SỐ DƯ TỒN KHO NGUYÊN VẬT LIỆU", subtitle, columns, rows);
    }
}
