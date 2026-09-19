package com.erp.queue_service.handler.store;

import com.erp.core.domain.Branch;
import com.erp.core.domain.ShiftReport;
import com.erp.core.domain.StoreDailyReport;
import com.erp.queue_service.export.ReportColumnDefinition;
import com.erp.queue_service.export.ReportDataContext;
import com.erp.queue_service.handler.ModuleReportHandler;
import com.erp.queue_service.messaging.ReportMessage;
import com.erp.queue_service.repository.BranchRepository;
import com.erp.queue_service.repository.ShiftReportRepository;
import com.erp.queue_service.repository.StoreDailyReportRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Handler trích xuất dữ liệu báo cáo cửa hàng và ca làm việc trong queue-service.
 */
@Component
public class StoreReportHandler implements ModuleReportHandler {

    private final StoreDailyReportRepository storeDailyReportRepository;
    private final ShiftReportRepository shiftReportRepository;
    private final BranchRepository branchRepository;

    public StoreReportHandler(StoreDailyReportRepository storeDailyReportRepository,
                              ShiftReportRepository shiftReportRepository,
                              BranchRepository branchRepository) {
        this.storeDailyReportRepository = storeDailyReportRepository;
        this.shiftReportRepository = shiftReportRepository;
        this.branchRepository = branchRepository;
    }

    @Override
    public boolean supports(String module) {
        return "STORE".equalsIgnoreCase(module);
    }

    @Override
    public String getBaseFileName(ReportMessage message) {
        if ("STORE_SHIFT_REPORT".equalsIgnoreCase(message.getReportType())) {
            return "BaoCaoChotCa";
        }
        return "BaoCaoNgayCuaHang";
    }

    @Override
    public ReportDataContext generateReportData(ReportMessage message) {
        if ("STORE_SHIFT_REPORT".equalsIgnoreCase(message.getReportType())) {
            return generateShiftReportData(message);
        }
        return generateDailyReportData(message);
    }

    private ReportDataContext generateDailyReportData(ReportMessage message) {
        Map<String, Object> params = message.getParams() != null ? message.getParams() : Collections.emptyMap();
        UUID branchId = message.getBranchId();

        String startDateStr = (String) params.get("startDate");
        String endDateStr = (String) params.get("endDate");
        String status = (String) params.get("status");

        LocalDate startDate = startDateStr != null ? LocalDate.parse(startDateStr) : null;
        LocalDate endDate = endDateStr != null ? LocalDate.parse(endDateStr) : null;

        Specification<StoreDailyReport> spec = (root, query, cb) -> {
            var predicates = cb.conjunction();
            if (branchId != null) {
                predicates = cb.and(predicates, cb.equal(root.get("branchId"), branchId));
            }
            if (startDate != null) {
                predicates = cb.and(predicates, cb.greaterThanOrEqualTo(root.get("businessDate"), startDate));
            }
            if (endDate != null) {
                predicates = cb.and(predicates, cb.lessThanOrEqualTo(root.get("businessDate"), endDate));
            }
            if (status != null && !status.isBlank()) {
                predicates = cb.and(predicates, cb.equal(root.get("status"), status));
            }
            return predicates;
        };

        List<StoreDailyReport> reports = storeDailyReportRepository.findAll(spec);
        Set<UUID> branchIds = reports.stream().map(StoreDailyReport::getBranchId).collect(Collectors.toSet());
        Map<UUID, Branch> branchMap = branchRepository.findAllById(branchIds).stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity()));

        List<ReportColumnDefinition> columns = List.of(
                ReportColumnDefinition.date("businessDate", "Ngày KD", 12),
                ReportColumnDefinition.text("branchName", "Chi nhánh", 22),
                ReportColumnDefinition.number("totalOrders", "Số đơn", 10),
                ReportColumnDefinition.currency("grossRevenue", "Doanh thu gộp", 16),
                ReportColumnDefinition.currency("discountAmount", "Giảm giá", 14),
                ReportColumnDefinition.currency("netRevenue", "Doanh thu thuần", 16),
                ReportColumnDefinition.currency("cashAmount", "Tiền mặt", 14),
                ReportColumnDefinition.currency("transferAmount", "Chuyển khoản", 14),
                ReportColumnDefinition.currency("openingCash", "Tiền mở két", 14),
                ReportColumnDefinition.currency("closingCash", "Tiền chốt két", 14),
                ReportColumnDefinition.text("status", "Trạng thái", 14)
        );

        List<Map<String, Object>> rows = new ArrayList<>();
        for (StoreDailyReport r : reports) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("businessDate", r.getBusinessDate());
            Branch b = branchMap.get(r.getBranchId());
            row.put("branchName", b != null ? b.getName() : r.getBranchId().toString());
            row.put("totalOrders", r.getTotalOrders());
            row.put("grossRevenue", r.getGrossRevenue());
            row.put("discountAmount", r.getDiscountAmount());
            row.put("netRevenue", r.getNetRevenue());
            row.put("cashAmount", r.getCashAmount());
            row.put("transferAmount", r.getTransferAmount());
            row.put("openingCash", r.getOpeningCash());
            row.put("closingCash", r.getClosingCash());
            row.put("status", r.getStatus());
            rows.add(row);
        }

        String subtitle = "Thời gian kết xuất: " + LocalDate.now();
        return new ReportDataContext("BÁO CÁO DOANH THU NGÀY CHI NHÁNH", subtitle, columns, rows);
    }

    private ReportDataContext generateShiftReportData(ReportMessage message) {
        Map<String, Object> params = message.getParams() != null ? message.getParams() : Collections.emptyMap();
        UUID branchId = message.getBranchId();

        String businessDateStr = (String) params.get("businessDate");
        String startDateStr = (String) params.get("startDate");
        String endDateStr = (String) params.get("endDate");
        String status = (String) params.get("status");

        LocalDate businessDate = businessDateStr != null ? LocalDate.parse(businessDateStr) : null;
        LocalDate startDate = startDateStr != null ? LocalDate.parse(startDateStr) : null;
        LocalDate endDate = endDateStr != null ? LocalDate.parse(endDateStr) : null;

        Specification<ShiftReport> spec = (root, query, cb) -> {
            var predicates = cb.conjunction();
            if (branchId != null) {
                predicates = cb.and(predicates, cb.equal(root.get("branchId"), branchId));
            }
            if (businessDate != null) {
                predicates = cb.and(predicates, cb.equal(root.get("businessDate"), businessDate));
            }
            if (startDate != null) {
                predicates = cb.and(predicates, cb.greaterThanOrEqualTo(root.get("businessDate"), startDate));
            }
            if (endDate != null) {
                predicates = cb.and(predicates, cb.lessThanOrEqualTo(root.get("businessDate"), endDate));
            }
            if (status != null && !status.isBlank()) {
                predicates = cb.and(predicates, cb.equal(root.get("status"), status));
            }
            return predicates;
        };

        List<ShiftReport> reports = shiftReportRepository.findAll(spec);
        Set<UUID> branchIds = reports.stream().map(ShiftReport::getBranchId).collect(Collectors.toSet());
        Map<UUID, Branch> branchMap = branchRepository.findAllById(branchIds).stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity()));

        List<ReportColumnDefinition> columns = List.of(
                ReportColumnDefinition.date("businessDate", "Ngày KD", 12),
                ReportColumnDefinition.text("branchName", "Chi nhánh", 22),
                ReportColumnDefinition.number("ordersCount", "Số đơn", 10),
                ReportColumnDefinition.currency("initialCash", "Tiền mở ca", 14),
                ReportColumnDefinition.currency("totalSales", "Tổng doanh thu", 16),
                ReportColumnDefinition.currency("cashSales", "Tiền mặt bán", 14),
                ReportColumnDefinition.currency("expectedCash", "Tiền lý thuyết", 15),
                ReportColumnDefinition.currency("actualCash", "Tiền thực đếm", 15),
                ReportColumnDefinition.currency("difference", "Chênh lệch", 14),
                ReportColumnDefinition.text("differenceReason", "Lý do lệch", 20),
                ReportColumnDefinition.text("status", "Trạng thái", 14)
        );

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ShiftReport r : reports) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("businessDate", r.getBusinessDate());
            Branch b = branchMap.get(r.getBranchId());
            row.put("branchName", b != null ? b.getName() : r.getBranchId().toString());
            row.put("ordersCount", r.getOrdersCount());
            row.put("initialCash", r.getInitialCash());
            row.put("totalSales", r.getTotalSales());
            row.put("cashSales", r.getCashSales());
            row.put("expectedCash", r.getExpectedCash());
            row.put("actualCash", r.getActualCash());
            row.put("difference", r.getDifference());
            row.put("differenceReason", r.getDifferenceReason() != null ? r.getDifferenceReason() : "-");
            row.put("status", r.getStatus());
            rows.add(row);
        }

        String subtitle = "Thời gian kết xuất: " + LocalDate.now();
        return new ReportDataContext("BÁO CÁO BIÊN BẢN CHỐT CA BÁN HÀNG", subtitle, columns, rows);
    }
}
