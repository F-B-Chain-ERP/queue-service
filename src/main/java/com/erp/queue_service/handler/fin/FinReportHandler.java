package com.erp.queue_service.handler.fin;

import com.erp.core.domain.Branch;
import com.erp.core.domain.BranchDailyFinancialSummary;
import com.erp.queue_service.export.ReportColumnDefinition;
import com.erp.queue_service.export.ReportDataContext;
import com.erp.queue_service.handler.ModuleReportHandler;
import com.erp.queue_service.messaging.ReportMessage;
import com.erp.queue_service.repository.BranchDailyFinancialSummaryRepository;
import com.erp.queue_service.repository.BranchRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Handler trích xuất dữ liệu báo cáo tài chính chi nhánh trong queue-service.
 */
@Component
public class FinReportHandler implements ModuleReportHandler {

    private final BranchDailyFinancialSummaryRepository summaryRepository;
    private final BranchRepository branchRepository;

    public FinReportHandler(BranchDailyFinancialSummaryRepository summaryRepository,
                            BranchRepository branchRepository) {
        this.summaryRepository = summaryRepository;
        this.branchRepository = branchRepository;
    }

    @Override
    public boolean supports(String module) {
        return "FIN".equalsIgnoreCase(module);
    }

    @Override
    public String getBaseFileName(ReportMessage message) {
        return "BaoCaoTaiChinhChiNhanh";
    }

    @Override
    public ReportDataContext generateReportData(ReportMessage message) {
        Map<String, Object> params = message.getParams() != null ? message.getParams() : Collections.emptyMap();
        UUID branchId = message.getBranchId();

        String fromDateStr = (String) params.get("fromDate");
        String toDateStr = (String) params.get("toDate");

        LocalDate fromDate = fromDateStr != null ? LocalDate.parse(fromDateStr) : null;
        LocalDate toDate = toDateStr != null ? LocalDate.parse(toDateStr) : null;

        Specification<BranchDailyFinancialSummary> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (branchId != null) {
                predicates.add(cb.equal(root.get("branchId"), branchId));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("businessDate"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("businessDate"), toDate));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        List<BranchDailyFinancialSummary> summaries = summaryRepository.findAll(spec);
        Set<UUID> branchIds = summaries.stream().map(BranchDailyFinancialSummary::getBranchId).collect(Collectors.toSet());
        Map<UUID, Branch> branchMap = branchRepository.findAllById(branchIds).stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity()));

        List<ReportColumnDefinition> columns = List.of(
                ReportColumnDefinition.date("businessDate", "Ngày KD", 12),
                ReportColumnDefinition.text("branchName", "Chi nhánh", 22),
                ReportColumnDefinition.number("orderCount", "Số đơn", 10),
                ReportColumnDefinition.currency("grossRevenue", "Doanh thu gộp", 16),
                ReportColumnDefinition.currency("discountAmount", "Giảm giá", 14),
                ReportColumnDefinition.currency("netRevenue", "Doanh thu thuần", 16),
                ReportColumnDefinition.currency("totalCogs", "Giá vốn (COGS)", 16),
                ReportColumnDefinition.currency("grossProfit", "Lợi nhuận gộp", 16),
                ReportColumnDefinition.currency("totalExpense", "Tổng chi phí", 15),
                ReportColumnDefinition.currency("netProfit", "Lợi nhuận ròng", 16),
                ReportColumnDefinition.text("status", "Trạng thái", 12)
        );

        List<Map<String, Object>> rows = new ArrayList<>();
        for (BranchDailyFinancialSummary s : summaries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("businessDate", s.getBusinessDate());
            Branch b = branchMap.get(s.getBranchId());
            row.put("branchName", b != null ? b.getName() : s.getBranchId().toString());
            row.put("orderCount", s.getOrderCount());
            row.put("grossRevenue", s.getGrossRevenue());
            row.put("discountAmount", s.getDiscountAmount());
            row.put("netRevenue", s.getNetRevenue());
            row.put("totalCogs", s.getTotalCogs());
            row.put("grossProfit", s.getGrossProfit());
            row.put("totalExpense", s.getTotalExpense());
            row.put("netProfit", s.getNetProfit());
            row.put("status", s.getStatus());
            rows.add(row);
        }

        String subtitle = "Thời gian kết xuất: " + LocalDate.now();
        return new ReportDataContext("BÁO CÁO TỔNG HỢP TÀI CHÍNH CHI NHÁNH", subtitle, columns, rows);
    }
}
