package com.erp.queue_service.handler.store;

import com.erp.core.constants.ReportExportConstants;
import com.erp.core.domain.Branch;
import com.erp.core.domain.ShiftReport;
import com.erp.core.domain.StoreDailyReport;
import com.erp.core.enums.ReportType;
import com.erp.core.report.ReportColumnDefinition;
import com.erp.core.report.ReportDataContext;
import com.erp.queue_service.handler.ModuleReportHandler;
import com.erp.queue_service.messaging.ReportMessage;
import com.erp.queue_service.repository.BranchRepository;
import com.erp.queue_service.repository.ShiftReportRepository;
import com.erp.queue_service.repository.StoreDailyReportRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Handler trích xuất dữ liệu báo cáo cửa hàng và ca làm việc trong queue-service.
 *
 * <p>Hỗ trợ 2 mẫu báo cáo theo hợp đồng {@link ReportExportConstants}:
 * <ul>
 *   <li>{@code STORE_DAILY_REPORT} — doanh thu theo ngày chi nhánh, kèm dòng tổng cộng.</li>
 *   <li>{@code STORE_SHIFT_REPORT} — biên bản chốt ca, kèm bảng kê mệnh giá tiền mặt
 *       chuẩn 09 mệnh giá (secondaryData, qua {@link DenominationParser}) và siêu dữ liệu
 *       người lập/người duyệt cho vùng ký.</li>
 * </ul>
 *
 * <p>Các phân nhánh đều chuẩn hoá reportType qua {@link ReportType}.</p>
 */
@Component
public class StoreReportHandler implements ModuleReportHandler {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final StoreDailyReportRepository storeDailyReportRepository;
    private final ShiftReportRepository shiftReportRepository;
    private final BranchRepository branchRepository;
    private final DenominationParser denominationParser;

    public StoreReportHandler(StoreDailyReportRepository storeDailyReportRepository,
                              ShiftReportRepository shiftReportRepository,
                              BranchRepository branchRepository,
                              DenominationParser denominationParser) {
        this.storeDailyReportRepository = storeDailyReportRepository;
        this.shiftReportRepository = shiftReportRepository;
        this.branchRepository = branchRepository;
        this.denominationParser = denominationParser;
    }

    @Override
    public boolean supports(String module) {
        return "STORE".equalsIgnoreCase(module);
    }

    @Override
    public String getBaseFileName(ReportMessage message) {
        if (ReportType.from(message.getReportType()) == ReportType.STORE_SHIFT_REPORT) {
            return "BaoCaoChotCa";
        }
        return "BaoCaoNgayCuaHang";
    }

    @Override
    public ReportDataContext generateReportData(ReportMessage message) {
        if (ReportType.from(message.getReportType()) == ReportType.STORE_SHIFT_REPORT) {
            return generateShiftReportData(message);
        }
        return generateDailyReportData(message);
    }

    private String branchName(Map<UUID, Branch> branchMap, UUID branchId) {
        Branch b = branchMap.get(branchId);
        return b != null ? b.getName() : (branchId != null ? branchId.toString() : null);
    }

    // ==== MẪU STORE_DAILY_REPORT ====

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
        Map<UUID, Branch> branchMap = reports.isEmpty()
                ? Collections.emptyMap()
                : branchRepository.findAllById(reports.stream()
                                .map(StoreDailyReport::getBranchId).collect(Collectors.toSet())).stream()
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
            row.put("branchName", branchName(branchMap, r.getBranchId()));
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

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("label", "TỔNG CỘNG");
        summary.put("dayCount", reports.size());
        summary.put("totalOrders", reports.stream().mapToInt(r -> r.getTotalOrders() != null ? r.getTotalOrders() : 0).sum());
        summary.put("grossRevenue", sumDaily(reports, StoreDailyReport::getGrossRevenue));
        summary.put("discountAmount", sumDaily(reports, StoreDailyReport::getDiscountAmount));
        summary.put("netRevenue", sumDaily(reports, StoreDailyReport::getNetRevenue));
        summary.put("cashAmount", sumDaily(reports, StoreDailyReport::getCashAmount));
        summary.put("transferAmount", sumDaily(reports, StoreDailyReport::getTransferAmount));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("branchName", branchName(branchMap, branchId));
        metadata.put("startDate", startDate);
        metadata.put("endDate", endDate);

        String subtitle = "Thời gian kết xuất: " + LocalDate.now();
        if (branchName(branchMap, branchId) != null) {
            subtitle += " | Chi nhánh: " + branchName(branchMap, branchId);
        }
        if (startDate != null && endDate != null) {
            subtitle += " | " + startDate + " - " + endDate;
        }

        return new ReportDataContext(
                "BÁO CÁO DOANH THU NGÀY CHI NHÁNH",
                subtitle,
                null,
                metadata,
                columns,
                rows,
                summary,
                List.of()
        );
    }

    // ==== MẪU STORE_SHIFT_REPORT ====

    private ReportDataContext generateShiftReportData(ReportMessage message) {
        Map<String, Object> params = message.getParams() != null ? message.getParams() : Collections.emptyMap();
        UUID branchId = message.getBranchId();

        String shiftReportIdStr = (String) params.get("shiftReportId");
        UUID shiftReportId = (shiftReportIdStr != null && !shiftReportIdStr.isBlank())
                ? UUID.fromString(shiftReportIdStr) : null;
        String businessDateStr = (String) params.get("businessDate");
        String startDateStr = (String) params.get("startDate");
        String endDateStr = (String) params.get("endDate");
        String status = (String) params.get("status");

        LocalDate businessDate = businessDateStr != null ? LocalDate.parse(businessDateStr) : null;
        LocalDate startDate = startDateStr != null ? LocalDate.parse(startDateStr) : null;
        LocalDate endDate = endDateStr != null ? LocalDate.parse(endDateStr) : null;

        Specification<ShiftReport> spec = (root, query, cb) -> {
            var predicates = cb.conjunction();
            if (shiftReportId != null) {
                predicates = cb.and(predicates, cb.equal(root.get("id"), shiftReportId));
            }
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
        Map<UUID, Branch> branchMap = reports.isEmpty()
                ? Collections.emptyMap()
                : branchRepository.findAllById(reports.stream()
                                .map(ShiftReport::getBranchId).collect(Collectors.toSet())).stream()
                        .collect(Collectors.toMap(Branch::getId, Function.identity()));

        List<ReportColumnDefinition> columns = List.of(
                ReportColumnDefinition.date("businessDate", "Ngày KD", 12),
                ReportColumnDefinition.text("branchName", "Chi nhánh", 22),
                ReportColumnDefinition.number("ordersCount", "Số đơn", 10),
                ReportColumnDefinition.currency("initialCash", "Tiền mở ca", 14),
                ReportColumnDefinition.currency("totalSales", "Tổng doanh thu", 16),
                ReportColumnDefinition.currency("cashSales", "Tiền mặt bán", 14),
                ReportColumnDefinition.currency("cardSales", "Quẹt thẻ", 14),
                ReportColumnDefinition.currency("bankTransferSales", "Chuyển khoản", 14),
                ReportColumnDefinition.currency("ewalletSales", "Ví điện tử", 14),
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
            row.put("branchName", branchName(branchMap, r.getBranchId()));
            row.put("ordersCount", r.getOrdersCount());
            row.put("initialCash", r.getInitialCash());
            row.put("totalSales", r.getTotalSales());
            row.put("cashSales", r.getCashSales());
            row.put("cardSales", r.getCardSales());
            row.put("bankTransferSales", r.getBankTransferSales());
            row.put("ewalletSales", r.getEwalletSales());
            row.put("expectedCash", r.getExpectedCash());
            row.put("actualCash", r.getActualCash());
            row.put("difference", r.getDifference());
            row.put("differenceReason", r.getDifferenceReason() != null ? r.getDifferenceReason() : "-");
            row.put("status", r.getStatus());
            rows.add(row);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("label", "TỔNG CỘNG CA");
        summary.put("shiftCount", reports.size());
        summary.put("ordersCount", reports.stream().mapToInt(r -> r.getOrdersCount() != null ? r.getOrdersCount() : 0).sum());
        summary.put("initialCash", sum(reports, ShiftReport::getInitialCash));
        summary.put("totalSales", sum(reports, ShiftReport::getTotalSales));
        summary.put("cashSales", sum(reports, ShiftReport::getCashSales));
        summary.put("cardSales", sum(reports, ShiftReport::getCardSales));
        summary.put("bankTransferSales", sum(reports, ShiftReport::getBankTransferSales));
        summary.put("ewalletSales", sum(reports, ShiftReport::getEwalletSales));
        summary.put("cashPayout", sum(reports, ShiftReport::getCashPayout));
        summary.put("expectedCash", sum(reports, ShiftReport::getExpectedCash));
        summary.put("actualCash", sum(reports, ShiftReport::getActualCash));
        summary.put("difference", sum(reports, ShiftReport::getDifference));

        // Bảng phụ: mệnh giá tiền mặt (chuẩn 09 mệnh giá) + siêu dữ liệu cho vùng ký nhận.
        List<Map<String, Object>> denominationRows = reports.isEmpty()
                ? List.of()
                : denominationParser.parseAll(reports.stream().map(ShiftReport::getCashDenominations).toList());
        List<Map<String, Object>> secondaryData = new ArrayList<>(shiftPaymentChannels(reports));
        secondaryData.addAll(denominationRows);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("branchName", branchName(branchMap, branchId));
        metadata.put("businessDate", businessDate);
        metadata.put("submittedBy", reports.isEmpty()
                ? null : Objects.toString(reports.get(0).getSubmittedById(), null));
        metadata.put("approvedBy", reports.isEmpty()
                ? null : Objects.toString(reports.get(0).getApprovedById(), null));
        metadata.put("submittedAt", reports.isEmpty()
                ? null : Objects.toString(reports.get(0).getSubmittedAt(), null));
        metadata.put("approvedAt", reports.isEmpty()
                ? null : Objects.toString(reports.get(0).getApprovedAt(), null));
        metadata.put("note", reports.isEmpty() ? null : reports.get(0).getNote());

        // Kiểm tra khớp giữa bảng kê mệnh giá và tiền mặt thực đếm (theo tài liệu thiết kế).
        BigDecimal actualCashTotal = sum(reports, ShiftReport::getActualCash);
        BigDecimal denominationsTotal = denominationParser.total(denominationRows);
        metadata.put("denominationsTotal", denominationsTotal);
        metadata.put("denominationsMatch", reports.isEmpty()
                || denominationsTotal.compareTo(actualCashTotal) == 0);

        String subtitle = "Thời gian kết xuất: " + LocalDate.now();
        if (branchName(branchMap, branchId) != null) {
            subtitle += " | Chi nhánh: " + branchName(branchMap, branchId);
        }
        if (businessDate != null) {
            subtitle += " | Ngày: " + businessDate;
        }

        return new ReportDataContext(
                "BÁO CÁO BIÊN BẢN CHỐT CA BÁN HÀNG",
                subtitle,
                null,
                metadata,
                columns,
                rows,
                summary,
                secondaryData
        );
    }

    // ==== HELPERS ====

    private BigDecimal sum(List<ShiftReport> reports, Function<ShiftReport, BigDecimal> extractor) {
        return reports.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .reduce(ZERO, BigDecimal::add);
    }

    private BigDecimal sumDaily(List<StoreDailyReport> reports, Function<StoreDailyReport, BigDecimal> extractor) {
        return reports.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .reduce(ZERO, BigDecimal::add);
    }

    /** Phân bổ doanh thu ca theo kênh thanh toán: tiền mặt / thẻ / chuyển khoản / ví điện tử. */
    private List<Map<String, Object>> shiftPaymentChannels(List<ShiftReport> reports) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("Tiền mặt", sum(reports, ShiftReport::getCashSales));
        totals.put("Quẹt thẻ", sum(reports, ShiftReport::getCardSales));
        totals.put("Chuyển khoản", sum(reports, ShiftReport::getBankTransferSales));
        totals.put("Ví điện tử", sum(reports, ShiftReport::getEwalletSales));
        totals.forEach((channel, amount) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("channel", channel);
            row.put("orderCount", null);
            row.put("amount", amount);
            rows.add(row);
        });
        return rows;
    }
}