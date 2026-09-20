package com.erp.queue_service.handler.store;

import com.erp.core.constants.ReportExportConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bộ phân tích và chuẩn hoá bảng kê mệnh giá tiền mặt Việt Nam.
 *
 * <p>Đầu vào linh hoạt (theo cách cửa hàng ghi phiếu chốt ca):
 * <ul>
 *   <li>JSON object {@code {"500000": 10, "200000": 3}} — mệnh giá → số tờ.</li>
 *   <li>JSON array {@code [{denomination|value|menhGia, count|quantity}]}.</li>
 *   <li>Chuỗi không parse được JSON — coi như {@code {chuỗi: 1}}.</li>
 * </ul>
 *
 * <p>Đầu ra luôn là đúng 09 dòng theo {@link ReportExportConstants#VND_DENOMINATIONS},
 * sắp xếp giảm dần — đúng template bảng mệnh giá trong biên bản chốt ca.
 * Mệnh giá không nằm trong 09 mệnh giá đang lưu hành sẽ được bỏ qua để giữ bảng chuẩn.</p>
 */
@Component
public class DenominationParser {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final ObjectMapper objectMapper;

    public DenominationParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parse một chuỗi JSON rồi trả về đúng 09 dòng mệnh giá (giảm dần), mệnh giá thiếu = 0 tờ.
     */
    public List<Map<String, Object>> parse(String json) {
        return buildRows(parseCounts(json));
    }

    /**
     * Gộp nhiều phiếu chốt ca thành một bảng kê 09 mệnh giá (cộng số tờ cùng mệnh giá).
     */
    public List<Map<String, Object>> parseAll(Collection<String> jsons) {
        Map<Long, Long> totals = new LinkedHashMap<>();
        for (String json : jsons) {
            if (json == null || json.isBlank()) {
                continue;
            }
            parseCounts(json).forEach((denomination, count) -> totals.merge(denomination, count, Long::sum));
        }
        return buildRows(totals);
    }

    /** Tổng tiền mặt khớp được từ bảng kê (đơn vị: đồng). */
    public BigDecimal total(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> row.get("amount"))
                .filter(Objects::nonNull)
                .map(amount -> amount instanceof BigDecimal bd ? bd : new BigDecimal(amount.toString()))
                .reduce(ZERO, BigDecimal::add);
    }

    private List<Map<String, Object>> buildRows(Map<Long, Long> counts) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Long denomination : ReportExportConstants.VND_DENOMINATIONS) {
            long count = counts.getOrDefault(denomination, 0L);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("denomination", ReportExportConstants.denominationLabel(denomination));
            row.put("value", denomination);
            row.put("count", count);
            row.put("amount", BigDecimal.valueOf(denomination).multiply(BigDecimal.valueOf(count)));
            rows.add(row);
        }
        return rows;
    }

    private Map<Long, Long> parseCounts(String json) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return counts;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isObject()) {
                node.fields().forEachRemaining(entry -> addEntry(counts, entry.getKey(), entry.getValue().asInt(0)));
            } else if (node.isArray()) {
                for (JsonNode item : node) {
                    String denomination = item.path("denomination").asText(item.path("value").asText(item.path("menhGia").asText("")));
                    int count = item.path("count").asInt(item.path("quantity").asInt(0));
                    addEntry(counts, denomination, count);
                }
            } else if (node.isNumber() || node.isTextual()) {
                // Giá trị đơn (vd: "50000") — coi như một mệnh giá duy nhất với 1 tờ.
                addEntry(counts, node.asText(), 1);
            }
        } catch (Exception ignored) {
            // Không phải JSON — coi như chuỗi số đơn {chuỗi: 1}.
            addEntry(counts, json.trim(), 1);
        }
        return counts;
    }

    private static void addEntry(Map<Long, Long> counts, String rawDenomination, int count) {
        Long denomination = toDenomination(rawDenomination);
        if (denomination == null) {
            return;
        }
        counts.merge(denomination, (long) count, Long::sum);
    }

    private static Long toDenomination(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.replaceAll("[^0-9]", "");
        if (normalized.isEmpty()) {
            return null;
        }
        long value;
        try {
            value = Long.parseLong(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
        for (Long denomination : ReportExportConstants.VND_DENOMINATIONS) {
            if (denomination.equals(value)) {
                return denomination;
            }
        }
        return null;
    }
}